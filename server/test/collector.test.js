import { test, before, after, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import pg from 'pg';
import { migrate } from '../src/db.js';
import { createCollector } from '../src/collect.js';
import { parseFeed,findServiceScript,findFeedBase,parseSourceTime,isStopNotice,SITE_URL,DEFAULT_FEED_URL } from '../src/crossings.js';

const testUrl=process.env.TEST_DATABASE_URL || 'postgresql://viaagora:viaagora_local_only@127.0.0.1:15439/viaagora_test';
if(!new URL(testUrl).pathname.endsWith('_test'))throw new Error('Testes exigem banco com sufixo _test.');
const pool=new pg.Pool({connectionString:testUrl});
const feed=JSON.parse(await readFile(new URL('./fixtures/feed.json',import.meta.url),'utf8'));
const script=await readFile(new URL('./fixtures/service.js',import.meta.url),'utf8');
const page='<html><script src="https://semil.sp.gov.br/travessias/wp-content/themes/travessias/assets/travessia-service-prd.js?v=2"></script></html>';
const quiet={info(){},warn(){}};
const json=(body,status=200)=>new Response(JSON.stringify(body),{status,headers:{'content-type':'application/json'}});
before(async()=>{await migrate(pool);});
after(async()=>{await pool.end();});
beforeEach(async()=>{await pool.query('TRUNCATE outbox_events, crossing_snapshots, collector_state');});

test('scraper finds the panel script and the active service address, ignoring commented lines',()=>{
  const src=findServiceScript(page);assert.match(src,/travessia-service-prd\.js/);
  assert.equal(findServiceScript('<html></html>'),null);
  assert.equal(findFeedBase(script),'https://drivetis-travessias.brazilsouth.cloudapp.azure.com:1444/TravessiaService/');
  assert.equal(findFeedBase('//var getInfo = "https://antigo/";\nvar x=1;'),null);
});
test('parser keeps only the three Baixada Santista crossings and normalizes their fields',()=>{
  const items=parseFeed(feed);
  assert.deepEqual(items.map(i=>i.crossingId).sort(),['bertioga-guaruja','santos-guaruja','santos-vicente-de-carvalho']);
  const santos=items.find(i=>i.crossingId==='santos-guaruja');
  assert.equal(typeof santos.waitA,'number');assert.equal(typeof santos.operating,'boolean');
  assert.ok(items.every(i=>!/<[a-z]/i.test(i.notice??'')));
  assert.equal(santos.notice,'Maré baixa. Cuidado ao embarcar e desembarcar.');
  assert.equal(items.find(i=>i.crossingId==='bertioga-guaruja').notice,null,'generic 0800 banner is not a notice');
  assert.equal(parseSourceTime('10/28/2026 9:16:41 PM'),'2026-10-29T00:16:41.000Z');
  assert.equal(parseSourceTime('12/01/2026 12:05:00 AM'),'2026-12-01T03:05:00.000Z');
  assert.equal(parseSourceTime('ontem'),null);
});
test('parser skips malformed entries and rejects the source refusal strings',()=>{
  const broken=[{...feed[1],EsperaPontoA:'quinze'},{...feed[1],EsperaPontoA:-1},feed[5]];
  assert.deepEqual(parseFeed(broken).map(i=>i.crossingId),['santos-vicente-de-carvalho']);
  assert.throws(()=>parseFeed('bloqueado'),/recusou/);assert.throws(()=>parseFeed({}),/inesperado/);
});
test('a crossing is stopped when it has no boats or the official notice says it was interrupted',()=>{
  for(const text of ['Travessia interrompida devido à neblina.','TRAVESSIA PARALISADA por mar agitado','Operação suspensa temporariamente','Paralisação por manutenção'])
    assert.equal(isStopNotice(text),true,text);
  for(const text of ['Travessia normalizada após interrupção.','Maré baixa. Cuidado ao embarcar e desembarcar.','Suspenso o transporte de veículos pesados.',null])
    assert.equal(isStopNotice(text),false,String(text));
  assert.equal(isStopNotice('Travessia interrompida para todos os veículos, inclusive pesados.'),true);
  assert.equal(isStopNotice('Operação suspensa por neblina; caminhões aguardam no acesso.'),true);
  const santos=feed[1];
  const [byNotice]=parseFeed([{...santos,AvisoUsuarioApp:'Travessia interrompida devido à neblina.'}]);
  assert.equal(byNotice.operating,false);assert.equal(byNotice.notice,'Travessia interrompida devido à neblina.');
  assert.equal(parseFeed([{...santos,NumEmbarca:0}])[0].operating,false);
  assert.equal(parseFeed([santos])[0].operating,true);
});
test('collector discovers the feed, saves readings and emits events only when something changes',async()=>{
  const calls=[];
  const fetchImpl=async url=>{calls.push(url);if(url===SITE_URL)return new Response(page);if(url.includes('travessia-service'))return new Response(script);return json(feed);};
  const collector=createCollector({pool,fetchImpl,log:quiet});
  assert.deepEqual(await collector.run(),{saved:3,changes:3});
  assert.deepEqual(calls.slice(0,2),[SITE_URL,'https://semil.sp.gov.br/travessias/wp-content/themes/travessias/assets/travessia-service-prd.js?v=2']);
  assert.deepEqual(await collector.run(),{saved:3,changes:0});
  assert.equal(calls.filter(u=>u===SITE_URL).length,1,'site is scanned only once while the address keeps working');
  assert.equal((await pool.query('SELECT count(*) FROM crossing_snapshots')).rows[0].count,'6');
  assert.equal((await pool.query("SELECT count(*) FROM outbox_events WHERE payload->>'type'='crossing.updated'")).rows[0].count,'3');
});
test('source failure keeps the last reading, is announced once and recovery is announced',async()=>{
  let online=true;
  const fetchImpl=async url=>{if(!online)throw new Error('ECONNREFUSED');if(url===SITE_URL)return new Response(page);if(url.includes('travessia-service'))return new Response(script);return json(feed);};
  const collector=createCollector({pool,fetchImpl,log:quiet});
  await collector.run();online=false;
  await assert.rejects(collector.run());await assert.rejects(collector.run());
  const state=(await pool.query('SELECT failures,last_error FROM collector_state')).rows[0];
  assert.equal(state.failures,2);assert.match(state.last_error,/ECONNREFUSED/);
  assert.equal((await pool.query('SELECT count(*) FROM crossing_snapshots')).rows[0].count,'3');
  online=true;await collector.run();
  const events=(await pool.query("SELECT payload->>'available' AS available FROM outbox_events WHERE payload->>'type'='source.status' ORDER BY created_at")).rows;
  assert.deepEqual(events.map(e=>e.available),['false','true']);
});
test('when the site cannot be scanned the collector falls back to the known service address',async()=>{
  const fetchImpl=async url=>url===DEFAULT_FEED_URL?json(feed):new Response('erro',{status:500});
  const collector=createCollector({pool,fetchImpl,log:quiet});
  assert.equal((await collector.run()).saved,3);assert.equal(collector.feedUrl,DEFAULT_FEED_URL);
});
