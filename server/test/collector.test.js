import { test, before, after, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import pg from 'pg';
import { migrar } from '../src/db.js';
import { criarColetor } from '../src/collect.js';
import { lerPainel,encontrarScriptDoPainel,encontrarEnderecoServico,lerHorarioFonte,ehAvisoDeParada,ENDERECO_SITE,ENDERECO_SERVICO_PADRAO } from '../src/crossings.js';

const testUrl=process.env.TEST_DATABASE_URL || 'postgresql://viaagora:viaagora_local_only@127.0.0.1:15439/viaagora_test';
if(!new URL(testUrl).pathname.endsWith('_test'))throw new Error('Testes exigem banco com sufixo _test.');
const pool=new pg.Pool({connectionString:testUrl});
const feed=JSON.parse(await readFile(new URL('./fixtures/feed.json',import.meta.url),'utf8'));
const script=await readFile(new URL('./fixtures/service.js',import.meta.url),'utf8');
const page='<html><script src="https://semil.sp.gov.br/travessias/wp-content/themes/travessias/assets/travessia-service-prd.js?v=2"></script></html>';
const quiet={info(){},warn(){}};
const json=(body,status=200)=>new Response(JSON.stringify(body),{status,headers:{'content-type':'application/json'}});
before(async()=>{await migrar(pool);});
after(async()=>{await pool.end();});
beforeEach(async()=>{await pool.query('TRUNCATE outbox_events, crossing_snapshots, collector_state');});

test('a varredura encontra o script do painel e o endereço ativo do serviço, ignorando linhas comentadas',()=>{
  const src=encontrarScriptDoPainel(page);assert.match(src,/travessia-service-prd\.js/);
  assert.equal(encontrarScriptDoPainel('<html></html>'),null);
  assert.equal(encontrarEnderecoServico(script),'https://drivetis-travessias.brazilsouth.cloudapp.azure.com:1444/TravessiaService/');
  assert.equal(encontrarEnderecoServico('//var getInfo = "https://antigo/";\nvar x=1;'),null);
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
test('o coletor descobre o serviço, grava as leituras e só gera eventos quando algo muda',async()=>{
  const calls=[];
  const fetchImpl=async url=>{calls.push(url);if(url===ENDERECO_SITE)return new Response(page);if(url.includes('travessia-service'))return new Response(script);return json(feed);};
  const collector=criarColetor({pool,buscarImpl:fetchImpl,log:quiet});
  assert.deepEqual(await collector.executar(),{gravadas:3,mudancas:3});
  assert.deepEqual(calls.slice(0,2),[ENDERECO_SITE,'https://semil.sp.gov.br/travessias/wp-content/themes/travessias/assets/travessia-service-prd.js?v=2']);
  assert.deepEqual(await collector.executar(),{gravadas:3,mudancas:0});
  assert.equal(calls.filter(u=>u===ENDERECO_SITE).length,1,'site is scanned only once while the address keeps working');
  assert.equal((await pool.query('SELECT count(*) FROM crossing_snapshots')).rows[0].count,'6');
  assert.equal((await pool.query("SELECT count(*) FROM outbox_events WHERE payload->>'type'='crossing.updated'")).rows[0].count,'3');
});
test('a falha da fonte mantém a última leitura, é avisada uma vez e a recuperação também é avisada',async()=>{
  let online=true;
  const fetchImpl=async url=>{if(!online)throw new Error('ECONNREFUSED');if(url===ENDERECO_SITE)return new Response(page);if(url.includes('travessia-service'))return new Response(script);return json(feed);};
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
test('quando o site não pode ser varrido o coletor usa o endereço conhecido do serviço',async()=>{
  const fetchImpl=async url=>url===ENDERECO_SERVICO_PADRAO?json(feed):new Response('erro',{status:500});
  const collector=criarColetor({pool,buscarImpl:fetchImpl,log:quiet});
  assert.equal((await collector.executar()).gravadas,3);assert.equal(collector.enderecoServico,ENDERECO_SERVICO_PADRAO);
});
