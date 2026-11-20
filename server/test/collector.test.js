import { test, before, after, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import pg from 'pg';
import { migrar } from '../src/db.js';
import { criarColetor } from '../src/collect.js';
import { lerPainel,lerPaginaAcquavias,lerMinutos,enderecoPagina,lerHorarioFonte,ehAvisoDeParada,ENDERECO_SITE,ENDERECO_SERVICO_PADRAO } from '../src/crossings.js';
import { paginaAcquavias } from './fixtures/acquavias.js';

const testUrl=process.env.TEST_DATABASE_URL || 'postgresql://viaagora:viaagora_local_only@127.0.0.1:15439/viaagora_test';
if(!new URL(testUrl).pathname.endsWith('_test'))throw new Error('Testes exigem banco com sufixo _test.');
const pool=new pg.Pool({connectionString:testUrl});
const feed=JSON.parse(await readFile(new URL('./fixtures/feed.json',import.meta.url),'utf8'));
const quiet={info(){},warn(){}};
const json=(body,status=200)=>new Response(JSON.stringify(body),{status,headers:{'content-type':'application/json'}});
// Página da Acquavias correspondente ao endereço pedido (ex.: https://acquavias.com.br/santos-guaruja/).
const idDaPagina=url=>url.startsWith(ENDERECO_SITE)?url.slice(ENDERECO_SITE.length).replace(/\/$/,''):null;
const site=(url,opcoes)=>new Response(paginaAcquavias(idDaPagina(url),opcoes));
before(async()=>{await migrar(pool);});
after(async()=>{await pool.end();});
beforeEach(async()=>{await pool.query('TRUNCATE outbox_events, crossing_snapshots, collector_state');});

test('a página da Acquavias dá as esperas, as embarcações, o clima de cada lado e o aviso',()=>{
  const santos=lerPaginaAcquavias(paginaAcquavias('santos-guaruja',{aviso:'Travessia com lentidão por motivo de maré forte.'}),'santos-guaruja');
  assert.deepEqual(santos,{crossingId:'santos-guaruja',waitA:30,waitB:40,boats:8,operating:true,weatherA:'Encoberto',weatherB:'Parcialmente Nublado',
    period:'Dia',notice:'Travessia com lentidão por motivo de maré forte.',sourceTime:null});
  assert.equal(enderecoPagina('santos-guaruja'),'https://acquavias.com.br/santos-guaruja/');
});
test('sem o corpo da página, a barra do topo basta; página sem a travessia é recusada',()=>{
  const vicente=lerPaginaAcquavias(paginaAcquavias('santos-vicente-de-carvalho',{semCorpo:true}),'santos-vicente-de-carvalho');
  assert.deepEqual([vicente.waitA,vicente.waitB,vicente.boats,vicente.weatherA,vicente.period],[12,12,3,'Encoberto','Noite']);
  assert.equal(lerPaginaAcquavias('<html><body>manutenção</body></html>','santos-guaruja'),null);
  assert.equal(lerPaginaAcquavias(paginaAcquavias('santos-guaruja',{esperas:['-','-'],topo:{esperas:['-','-']}}),'santos-guaruja'),null);
});
test('as esperas aceitam minutos e horas, e a travessia parada é detectada',()=>{
  assert.deepEqual(['45 min','1h 10min','2 h','0 min','-'].map(lerMinutos),[45,70,120,0,null]);
  const parada=lerPaginaAcquavias(paginaAcquavias('bertioga-guaruja',{aviso:'Travessia interrompida devido à neblina.'}),'bertioga-guaruja');
  assert.equal(parada.operating,false);assert.equal(parada.notice,'Travessia interrompida devido à neblina.');
  assert.equal(lerPaginaAcquavias(paginaAcquavias('bertioga-guaruja',{topo:{barcos:0}}),'bertioga-guaruja').operating,false);
  assert.equal(lerPaginaAcquavias(paginaAcquavias('bertioga-guaruja',{aviso:'0800-773-3711 atendimento 24 horas'}),'bertioga-guaruja').notice,null);
});
test('a leitura mantém só as três travessias da Baixada Santista e normaliza os campos',()=>{
  const items=lerPainel(feed);
  assert.deepEqual(items.map(i=>i.crossingId).sort(),['bertioga-guaruja','santos-guaruja','santos-vicente-de-carvalho']);
  const santos=items.find(i=>i.crossingId==='santos-guaruja');
  assert.equal(typeof santos.waitA,'number');assert.equal(typeof santos.operating,'boolean');
  assert.ok(items.every(i=>!/<[a-z]/i.test(i.notice??'')));
  assert.equal(santos.notice,'Maré baixa. Cuidado ao embarcar e desembarcar.');
  assert.equal(items.find(i=>i.crossingId==='bertioga-guaruja').notice,null,'generic 0800 banner is not a notice');
  assert.equal(lerHorarioFonte('10/28/2026 9:16:41 PM'),'2026-10-29T00:16:41.000Z');
  assert.equal(lerHorarioFonte('12/01/2026 12:05:00 AM'),'2026-12-01T03:05:00.000Z');
  assert.equal(lerHorarioFonte('ontem'),null);
});
test('a leitura ignora itens fora do formato e recusa as mensagens de recusa da fonte',()=>{
  const broken=[{...feed[1],EsperaPontoA:'quinze'},{...feed[1],EsperaPontoA:-1},feed[5]];
  assert.deepEqual(lerPainel(broken).map(i=>i.crossingId),['santos-vicente-de-carvalho']);
  assert.throws(()=>lerPainel('bloqueado'),/recusou/);assert.throws(()=>lerPainel({}),/inesperado/);
});
test('a travessia está parada quando não há embarcações ou o aviso oficial diz que foi interrompida',()=>{
  for(const text of ['Travessia interrompida devido à neblina.','TRAVESSIA PARALISADA por mar agitado','Operação suspensa temporariamente','Paralisação por manutenção'])
    assert.equal(ehAvisoDeParada(text),true,text);
  for(const text of ['Travessia normalizada após interrupção.','Maré baixa. Cuidado ao embarcar e desembarcar.','Suspenso o transporte de veículos pesados.',null])
    assert.equal(ehAvisoDeParada(text),false,String(text));
  assert.equal(ehAvisoDeParada('Travessia interrompida para todos os veículos, inclusive pesados.'),true);
  assert.equal(ehAvisoDeParada('Operação suspensa por neblina; caminhões aguardam no acesso.'),true);
  const santos=feed[1];
  const [byNotice]=lerPainel([{...santos,AvisoUsuarioApp:'Travessia interrompida devido à neblina.'}]);
  assert.equal(byNotice.operating,false);assert.equal(byNotice.notice,'Travessia interrompida devido à neblina.');
  assert.equal(lerPainel([{...santos,NumEmbarca:0}])[0].operating,false);
  assert.equal(lerPainel([santos])[0].operating,true);
});
test('o coletor lê as três páginas da Acquavias, grava as leituras e só gera eventos quando algo muda',async()=>{
  const calls=[];
  const fetchImpl=async url=>{calls.push(url);return site(url);};
  const collector=criarColetor({pool,buscarImpl:fetchImpl,log:quiet});
  assert.deepEqual(await collector.executar(),{gravadas:3,mudancas:3});
  assert.deepEqual(calls.sort(),['bertioga-guaruja','santos-guaruja','santos-vicente-de-carvalho'].map(enderecoPagina));
  assert.equal(collector.enderecoServico,ENDERECO_SITE);
  assert.deepEqual(await collector.executar(),{gravadas:3,mudancas:0});
  assert.equal((await pool.query('SELECT count(*) FROM crossing_snapshots')).rows[0].count,'6');
  assert.equal((await pool.query("SELECT count(*) FROM outbox_events WHERE payload->>'type'='crossing.updated'")).rows[0].count,'3');
  assert.equal((await pool.query('SELECT feed_url FROM collector_state')).rows[0].feed_url,ENDERECO_SITE);
});
test('se só uma página falhar, as outras travessias continuam sendo gravadas',async()=>{
  const fetchImpl=async url=>url.includes('bertioga')?new Response('erro',{status:502}):site(url);
  assert.equal((await criarColetor({pool,buscarImpl:fetchImpl,log:quiet}).executar()).gravadas,2);
});
test('a falha da fonte mantém a última leitura, é avisada uma vez e a recuperação também é avisada',async()=>{
  let online=true;
  const fetchImpl=async url=>{if(!online)throw new Error('ECONNREFUSED');return site(url);};
  const collector=criarColetor({pool,buscarImpl:fetchImpl,log:quiet});
  await collector.executar();online=false;
  await assert.rejects(collector.executar());await assert.rejects(collector.executar());
  const state=(await pool.query('SELECT failures,last_error FROM collector_state')).rows[0];
  assert.equal(state.failures,2);assert.match(state.last_error,/ECONNREFUSED/);
  assert.equal((await pool.query('SELECT count(*) FROM crossing_snapshots')).rows[0].count,'3');
  online=true;await collector.executar();
  const events=(await pool.query("SELECT payload->>'available' AS available FROM outbox_events WHERE payload->>'type'='source.status' ORDER BY created_at")).rows;
  assert.deepEqual(events.map(e=>e.available),['false','true']);
});
test('quando o site da Acquavias falha o coletor usa o serviço JSON de reserva',async()=>{
  const fetchImpl=async url=>url===ENDERECO_SERVICO_PADRAO?json(feed):new Response('erro',{status:500});
  const collector=criarColetor({pool,buscarImpl:fetchImpl,log:quiet});
  assert.equal((await collector.executar()).gravadas,3);assert.equal(collector.enderecoServico,ENDERECO_SERVICO_PADRAO);
  const semNada=criarColetor({pool,buscarImpl:async()=>new Response('erro',{status:503}),log:quiet});
  await assert.rejects(semNada.executar(),/reserva/);
});
