import { test, before, after, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import pg from 'pg';
import { buildApp } from '../src/app.js';
import { migrate } from '../src/db.js';
import { recordSnapshots } from '../src/domain.js';

const testUrl=process.env.TEST_DATABASE_URL || 'postgresql://viaagora:viaagora_local_only@127.0.0.1:15439/viaagora_test';
if(!new URL(testUrl).pathname.endsWith('_test'))throw new Error('Testes exigem banco com sufixo _test.');
const pool = new pg.Pool({connectionString:testUrl});
let app;
before(async()=>{await migrate(pool);app=await buildApp({pool,brokerStatus:()=>false,replica:'api-teste'});await app.ready();});
after(async()=>{await app?.close();await pool.end();});
beforeEach(async()=>{await pool.query('TRUNCATE outbox_events, reports, participants, crossing_snapshots, collector_state, node_heartbeats, event_deliveries, system_counters CASCADE');});
const payload={crossingId:'santos-guaruja',side:'A',category:'fila',waitMinutes:40,description:'Fila chega até o canal 6',latitude:-23.98,longitude:-46.30};
async function participant(){const r=await app.inject({method:'POST',url:'/v1/participants'});assert.equal(r.statusCode,201);return r.json();}
// Uma leitura das três travessias, no formato que o coletor grava.
const readings=()=>['santos-guaruja','santos-vicente-de-carvalho','bertioga-guaruja'].map(crossingId=>({crossingId,waitA:15,waitB:15,boats:3,operating:true,
  weatherA:null,weatherB:null,period:'Dia',notice:null,sourceTime:new Date().toISOString()}));
async function create(p,body=payload,key=randomUUID()){return app.inject({method:'POST',url:'/v1/reports',headers:{authorization:`Bearer ${p.token}`,'idempotency-key':key},payload:body});}

test('rejects missing token and malformed input without writing a report',async()=>{
  assert.equal((await app.inject({method:'POST',url:'/v1/reports',payload})).statusCode,401);
  const p=await participant();
  for(const bad of [{...payload,crossingId:'sao-sebastiao-ilhabela'},{...payload,side:'C'},{...payload,category:'outro'},{...payload,waitMinutes:-5},
    {...payload,waitMinutes:12.5},{...payload,latitude:91},{...payload,description:'x'.repeat(401)},{...payload,extra:true}]) assert.equal((await create(p,bad)).statusCode,400);
  assert.equal((await pool.query('SELECT count(*) FROM reports')).rows[0].count,'0');
});
test('accepts every category, optional wait and description, and persists an outbox event atomically',async()=>{
  const p=await participant();
  for(const category of ['fila','embarque_lento','interrompida','clima','acidente']){
    const r=await create(p,{...payload,category});assert.equal(r.statusCode,201);
    assert.equal(r.json().report.category,category);assert.equal(r.json().report.status,'active');
  }
  const {waitMinutes,description,...minimal}=payload;
  const r=await create(p,minimal);assert.equal(r.statusCode,201);assert.equal(r.json().report.waitMinutes,null);assert.equal(r.json().report.description,'');
  assert.equal((await pool.query('SELECT count(*) FROM outbox_events WHERE published_at IS NULL')).rows[0].count,'6');
  assert.equal((await app.inject({url:'/v1/reports?category=clima'})).json().reports.length,1);
  assert.equal((await app.inject({url:'/v1/reports?crossing=bertioga-guaruja'})).json().reports.length,0);
});
test('concurrent retries of one request create exactly one report and event',async()=>{
  const p=await participant(), key=randomUUID();
  const results=await Promise.all(Array.from({length:6},()=>create(p,payload,key)));
  assert.ok(results.every(r=>[200,201].includes(r.statusCode)));
  assert.equal(new Set(results.map(r=>r.json().report.id)).size,1);
  assert.equal((await pool.query('SELECT count(*) FROM outbox_events')).rows[0].count,'1');
  assert.equal((await create(p,{...payload,description:'Pedido diferente'},key)).statusCode,409);
});
test('only the author can close a report and repeated closing creates no extra event',async()=>{
  const author=await participant(), other=await participant();
  const id=(await create(author)).json().report.id;
  const resolve=p=>app.inject({method:'PATCH',url:`/v1/reports/${id}/resolve`,headers:{authorization:`Bearer ${p.token}`}});
  assert.equal((await resolve(other)).statusCode,403);
  const r=await resolve(author);assert.equal(r.statusCode,200);assert.equal(r.json().report.version,2);
  assert.equal((await resolve(author)).json().report.version,2);
  assert.equal((await pool.query('SELECT count(*) FROM outbox_events')).rows[0].count,'2');
  assert.equal((await app.inject({url:'/v1/reports?status=active'})).json().reports.length,0);
});
test('crossings combine the latest official reading with the community median per side',async()=>{
  let list=(await app.inject({url:'/v1/crossings'})).json();
  assert.equal(list.crossings.length,3);assert.ok(list.crossings.every(c=>c.status===null));assert.equal(list.source.available,false);
  await recordSnapshots(pool,readings());
  const statuses=readings();statuses[0]={...statuses[0],waitA:25,waitB:10};
  await recordSnapshots(pool,statuses,'https://exemplo/PT');
  const p=await participant();
  for(const wait of [30,50,60])assert.equal((await create(p,{...payload,waitMinutes:wait})).statusCode,201);
  await create(p,{...payload,side:'B',waitMinutes:null});
  list=(await app.inject({url:'/v1/crossings'})).json();
  const santos=list.crossings.find(c=>c.id==='santos-guaruja');
  assert.equal(santos.status.waitA,25);assert.equal(santos.status.source,'oficial');assert.equal(santos.status.stale,false);
  assert.deepEqual(santos.community.A,{waitMinutes:50,reports:3});assert.equal(santos.community.B,null);assert.equal(santos.activeReports,4);
  assert.equal(santos.pointA.name,'Santos');assert.equal(list.source.available,true);assert.equal(list.source.mode,'oficial');
});
test('readings older than ten minutes are flagged as stale',async()=>{
  await recordSnapshots(pool,readings());
  await pool.query("UPDATE crossing_snapshots SET collected_at=now()-interval '11 minutes'");
  await pool.query("UPDATE collector_state SET last_success_at=now()-interval '11 minutes'");
  const list=(await app.inject({url:'/v1/crossings'})).json();
  assert.ok(list.crossings.every(c=>c.status.stale));assert.equal(list.source.available,false);
});
test('history reports hourly averages and the best hour',async()=>{
  for(const [hour,a,b] of [[3,1,1],[7,60,40],[13,10,20],[18,45,5]])
    await pool.query(`INSERT INTO crossing_snapshots(crossing_id,wait_a,wait_b,boats,operating,source,collected_at)
      VALUES('santos-guaruja',$1,$2,3,true,'oficial',(date_trunc('day',now() AT TIME ZONE 'America/Sao_Paulo')-interval '1 day'+make_interval(hours=>$3)) AT TIME ZONE 'America/Sao_Paulo')`,[a,b,hour]);
  const r=(await app.inject({url:'/v1/crossings/santos-guaruja/history?days=7'})).json();
  assert.deepEqual(r.hourly.map(h=>h.hour),[3,7,13,18]);assert.deepEqual(r.bestHour,{A:13,B:18});
  assert.equal((await app.inject({url:'/v1/crossings/sao-sebastiao-ilhabela/history'})).statusCode,404);
  assert.equal((await app.inject({url:'/v1/crossings/santos-guaruja/history?days=90'})).statusCode,400);
});
test('stopped readings never become the best hour and stop reports are flagged on the crossing',async()=>{
  for(const [hour,wait,operating] of [[9,40,true],[11,0,false],[15,25,true]])
    await pool.query(`INSERT INTO crossing_snapshots(crossing_id,wait_a,wait_b,boats,operating,source,collected_at)
      VALUES('santos-guaruja',$1,$1,$2,$3,'oficial',(date_trunc('day',now() AT TIME ZONE 'America/Sao_Paulo')-interval '1 day'+make_interval(hours=>$4)) AT TIME ZONE 'America/Sao_Paulo')`,[wait,operating?3:0,operating,hour]);
  const r=(await app.inject({url:'/v1/crossings/santos-guaruja/history'})).json();
  assert.deepEqual(r.hourly.map(h=>h.hour),[9,15]);assert.deepEqual(r.bestHour,{A:15,B:15});
  const p=await participant();
  assert.equal((await create(p,{...payload,category:'interrompida',waitMinutes:null})).statusCode,201);
  await create(p,{...payload,category:'fila'});
  const santos=(await app.inject({url:'/v1/crossings'})).json().crossings.find(c=>c.id==='santos-guaruja');
  assert.equal(santos.stopReports,1);assert.equal(santos.activeReports,2);
  assert.equal(santos.route.length,2);assert.deepEqual(santos.route[0],[santos.pointA.latitude,santos.pointA.longitude]);
});
test('broker outage does not prevent durable writes and health reports degradation',async()=>{
  const p=await participant();assert.equal((await create(p)).statusCode,201);
  const health=await app.inject({url:'/health'});assert.equal(health.statusCode,503);
  assert.equal(health.json().services.database,'up');assert.equal(health.json().services.broker,'down');assert.equal(health.json().services.collector,'down');
});
test('public data never contains participant token or token hash',async()=>{
  const p=await participant();await create(p);
  const r=await app.inject({url:'/v1/reports'});assert.ok(!r.body.includes(p.token));assert.ok(!r.body.includes('tokenHash'));
  assert.equal((await app.inject({url:'/v1/reports?status=invalid'})).statusCode,400);
});
test('system view shows which processes are alive, the outbox and repeated sends that were ignored',async()=>{
  const {startHeartbeat}=await import('../src/heartbeat.js');
  const beats=[startHeartbeat(pool,{node:'api-1',role:'api',info:()=>({clients:2,broker:true})}),startHeartbeat(pool,{node:'worker',role:'worker'})];
  await Promise.all(beats.map(b=>b.beat()));beats.forEach(b=>b.stop());
  await pool.query(`INSERT INTO node_heartbeats(node,role,last_seen) VALUES('collector','collector',now()-interval '40 seconds')`);
  const p=await participant(),key=randomUUID();
  const first=await create(p,payload,key);assert.equal(first.statusCode,201);
  assert.equal((await create(p,payload,key)).statusCode,200);assert.equal((await create(p,payload,key)).statusCode,200);
  const r=await app.inject({url:'/v1/system'});assert.equal(r.statusCode,200);
  const s=r.json();
  assert.equal(s.servedBy,'api-teste');
  const byName=Object.fromEntries(s.nodes.map(n=>[n.node,n]));
  assert.equal(byName['api-1'].alive,true);assert.equal(byName['api-1'].info.clients,2);assert.equal(byName.worker.alive,true);
  assert.equal(byName.collector.alive,false,'a process that stopped beating is shown as down');
  assert.equal(s.services.broker,'up','the broker is up when a live API reports it connected');
  assert.equal(s.counters.idempotentReplays,2);
  assert.deepEqual({pending:s.outbox.pending,lastHour:s.outbox.lastHour},{pending:1,lastHour:1});
  assert.equal(s.events.length,1);assert.equal(s.events[0].type,'report.created');assert.equal(s.events[0].origin,'api-teste');
  assert.equal(s.events[0].publishedAt,null);assert.deepEqual(s.events[0].deliveries,[]);
  assert.ok(!r.body.includes(p.token));
});
