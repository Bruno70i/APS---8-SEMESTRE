import {test,before,after} from 'node:test';
import assert from 'node:assert/strict';
import {once} from 'node:events';
import {randomUUID} from 'node:crypto';
import {fork} from 'node:child_process';
import net from 'node:net';
import pg from 'pg';
import WebSocket from 'ws';
import {montarApp} from '../src/app.js';
import {migrar} from '../src/db.js';
import {publicarLote} from '../src/broker.js';
import {readFile} from 'node:fs/promises';
import {criarColetor} from '../src/collect.js';

const testUrl=process.env.TEST_DATABASE_URL||'postgresql://viaagora:viaagora_local_only@127.0.0.1:15439/viaagora_test';
if(!new URL(testUrl).pathname.endsWith('_test'))throw new Error('Testes exigem banco com sufixo _test.');
const pool=new pg.Pool({connectionString:testUrl});
const brokerUrl=process.env.RABBITMQ_URL||'amqp://viaagora:viaagora_local_only@127.0.0.1:15679';
const feed=await readFile(new URL('./fixtures/feed.json',import.meta.url),'utf8');
const apps=[],sockets=[];
const wait=ms=>new Promise(r=>setTimeout(r,ms));
async function until(fn){const deadline=Date.now()+10000;while(Date.now()<deadline){if(await fn())return;await wait(50);}throw new Error('Condição não atingida em 10s');}
before(async()=>{await migrar(pool);await pool.query('TRUNCATE outbox_events,reports,participants,event_deliveries CASCADE');});
after(async()=>{for(const s of sockets)s.terminate();for(const a of apps)await a.close();await pool.end();});
test('duas réplicas da API recuperam a outbox pendente com horários em ordem mesmo com a gravação da publicação atrasada',async()=>{
  for(let i=0;i<2;i++){
    const app=await montarApp({pool,urlBroker:brokerUrl,replica:`api-${i+1}`});apps.push(app);await app.listen({host:'127.0.0.1',port:0});
    await until(async()=>(await app.inject({url:'/health'})).statusCode===200);
    const socket=new WebSocket(`ws://127.0.0.1:${app.server.address().port}/v1/events`);sockets.push(socket);await once(socket,'open');
  }
  const received=[[],[]];sockets.forEach((s,i)=>s.on('message',raw=>received[i].push(JSON.parse(raw))));
  const person=(await apps[0].inject({method:'POST',url:'/v1/participants'})).json();
  const result=await apps[0].inject({method:'POST',url:'/v1/reports',headers:{authorization:`Bearer ${person.token}`,'idempotency-key':randomUUID()},payload:{crossingId:'santos-guaruja',side:'B',category:'fila',waitMinutes:35,latitude:-23.98,longitude:-46.29}});
  assert.equal(result.statusCode,201);const id=result.json().report.id;
  await assert.rejects(publicarLote(pool,'amqp://127.0.0.1:1'));
  assert.equal((await pool.query('SELECT count(*) FROM outbox_events WHERE published_at IS NULL')).rows[0].count,'1');
  // A entrega pode ocorrer antes de o worker terminar de persistir a confirmação.
  // Atrasa essa gravação para reproduzir a corrida sem depender da velocidade da máquina.
  const delayedMarkPool={connect:async()=>{
    const client=await pool.connect();
    return {query:async(sql,args)=>{
      if(sql.startsWith('UPDATE outbox_events SET published_at')){
        await until(async()=>(await pool.query('SELECT count(*)::int AS total FROM event_deliveries WHERE event_id=$1',[args[0]])).rows[0].total===2);
        await wait(30);
      }
      return client.query(sql,args);
    },release:()=>client.release()};
  }};
  assert.equal(await publicarLote(delayedMarkPool,brokerUrl),1);
  const delayedTrace=(await apps[0].inject({url:'/v1/system'})).json().events[0];
  assert.equal(delayedTrace.deliveries.length,2);
  assert.ok(delayedTrace.deliveries.every(d=>d.deliveredAt>=delayedTrace.publishedAt),JSON.stringify(delayedTrace));
  await until(()=>received.every(messages=>messages.some(e=>e.reportId===id)));
  for(const app of apps){const rows=(await app.inject({url:'/v1/reports'})).json().reports;assert.ok(rows.some(r=>r.id===id));}
  assert.equal(await publicarLote(pool,brokerUrl),0);
  assert.equal((await pool.query('SELECT count(*) FROM outbox_events WHERE published_at IS NULL')).rows[0].count,'0');
});
test('cada réplica se apresenta, carimba o que entregou e a rota do sistema mostra o caminho completo',async()=>{
  const fresh=new WebSocket(`ws://127.0.0.1:${apps[1].server.address().port}/v1/events`);sockets.push(fresh);
  const [ready]=await once(fresh,'message');assert.equal(JSON.parse(ready).replica,'api-2');
  const received=[[],[]];sockets.slice(0,2).forEach((s,i)=>s.on('message',raw=>received[i].push(JSON.parse(raw))));
  const person=(await apps[1].inject({method:'POST',url:'/v1/participants'})).json();
  const id=(await apps[1].inject({method:'POST',url:'/v1/reports',headers:{authorization:`Bearer ${person.token}`,'idempotency-key':randomUUID()},
    payload:{crossingId:'bertioga-guaruja',side:'A',category:'clima',latitude:-23.85,longitude:-46.14}})).json().report.id;
  assert.equal(await publicarLote(pool,brokerUrl),1);
  await until(()=>received.every(messages=>messages.some(e=>e.reportId===id)));
  assert.deepEqual(received.map(m=>m.find(e=>e.reportId===id).via),['api-1','api-2']);
  assert.ok(received.every(m=>m.find(e=>e.reportId===id).origin==='api-2'));
  const traceOf=async()=>(await apps[0].inject({url:'/v1/system'})).json().events.find(e=>e.type==='report.created'&&e.origin==='api-2');
  await until(async()=>(await traceOf())?.deliveries.length===2);
  const trace=await traceOf();
  assert.ok(trace.publishedAt>=trace.createdAt);
  assert.deepEqual(trace.deliveries.map(d=>d.replica),['api-1','api-2']);
  assert.ok(trace.deliveries.every(d=>d.deliveredAt>=trace.publishedAt&&d.clients>=1));
});
test('uma leitura coletada chega aos clientes de todas as réplicas da API',async()=>{
  const received=[[],[]];sockets.slice(0,2).forEach((s,i)=>s.on('message',raw=>received[i].push(JSON.parse(raw))));
  await pool.query('TRUNCATE crossing_snapshots,collector_state');
  await criarColetor({pool,enderecoServico:'https://exemplo/feed',buscarImpl:async()=>new Response(feed)}).executar();
  assert.equal(await publicarLote(pool,brokerUrl),3);
  await until(()=>received.every(messages=>messages.filter(e=>e.type==='crossing.updated').length===3));
  const list=(await apps[1].inject({url:'/v1/crossings'})).json();
  assert.ok(list.crossings.every(c=>c.status&&c.status.source==='oficial'));
});
test('mensagem WebSocket grande demais desliga o cliente sem derrubar a API',async()=>{
  const child=fork(new URL('./fixtures/ws-server.js',import.meta.url),[],{silent:true,env:{...process.env,TEST_DATABASE_URL:testUrl,RABBITMQ_URL:brokerUrl}});
  let errors='';child.stderr.on('data',chunk=>{errors+=chunk;});
  try{
    const [{port}]=await once(child,'message');
    const socket=new WebSocket(`ws://127.0.0.1:${port}/v1/events`);
    socket.on('error',()=>{});await once(socket,'open');
    const closed=once(socket,'close');socket.send('x'.repeat(1025));await closed;await wait(150);
    assert.equal(child.exitCode,null,errors);
    assert.equal((await fetch(`http://127.0.0.1:${port}/v1/reports`)).status,200);
  }finally{if(child.exitCode===null)child.kill();}
});
test('ao se recuperar, a réplica pede aos clientes que busquem tudo de novo após um aviso perdido',async()=>{
  const upstream=new URL(brokerUrl),links=new Set();
  const proxy=net.createServer(client=>{
    const target=net.connect(Number(upstream.port)||5672,upstream.hostname);
    links.add(client);links.add(target);
    client.on('error',()=>target.destroy());target.on('error',()=>client.destroy());
    client.on('close',()=>{links.delete(client);target.destroy();});target.on('close',()=>{links.delete(target);client.destroy();});
    client.pipe(target);target.pipe(client);
  });
  await new Promise(resolve=>proxy.listen(0,'127.0.0.1',resolve));
  const proxyPort=proxy.address().port;
  const routed=new URL(brokerUrl);routed.hostname='127.0.0.1';routed.port=String(proxyPort);
  const app=await montarApp({pool,urlBroker:routed.toString()});apps.push(app);
  await app.listen({host:'127.0.0.1',port:0});
  await until(async()=>(await app.inject({url:'/health'})).statusCode===200);
  const socket=new WebSocket(`ws://127.0.0.1:${app.server.address().port}/v1/events`);sockets.push(socket);
  const messages=[];socket.on('message',raw=>messages.push(JSON.parse(raw)));await once(socket,'open');
  try{
    for(const link of links)link.destroy();await new Promise(resolve=>proxy.close(resolve));
    await until(async()=>(await app.inject({url:'/health'})).statusCode===503);
    const person=(await app.inject({method:'POST',url:'/v1/participants'})).json();
    const created=await app.inject({method:'POST',url:'/v1/reports',headers:{authorization:`Bearer ${person.token}`,'idempotency-key':randomUUID()},payload:{crossingId:'bertioga-guaruja',side:'A',category:'interrompida',description:'Balsa parada para manutenção',latitude:-23.85,longitude:-46.14}});
    const id=created.json().report.id;await publicarLote(pool,brokerUrl);
    assert.ok(!messages.some(e=>e.reportId===id));
    await new Promise(resolve=>proxy.listen(proxyPort,'127.0.0.1',resolve));
    await until(()=>messages.some(e=>e.type==='resync'&&e.connected));
    assert.ok(messages.some(e=>e.type==='broker.status'&&e.connected===false));
    const snapshot=(await app.inject({url:'/v1/reports'})).json().reports;
    assert.ok(snapshot.some(item=>item.id===id));
  }finally{
    socket.terminate();await app.close();apps.splice(apps.indexOf(app),1);
    for(const link of links)link.destroy();if(proxy.listening)await new Promise(resolve=>proxy.close(resolve));
  }
});
