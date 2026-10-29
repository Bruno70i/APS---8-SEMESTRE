// Processo coletor: lê o painel oficial a cada minuto, um único processo para todos os usuários.
import {criarPool,migrar} from './db.js';
import {criarColetor} from './collect.js';
import {nomeDoProcesso,iniciarSinalDeVida} from './heartbeat.js';
const pool=criarPool();await migrar(pool);let encerrando=false;
process.on('SIGINT',()=>{encerrando=true;});process.on('SIGTERM',()=>{encerrando=true;});
const intervalo=Math.max(30_000,Number(process.env.COLLECT_INTERVAL_MS||60_000));
const coletor=criarColetor({pool,enderecoServico:process.env.TRAVESSIAS_FEED_URL||null});
console.log(`ViaAgora: coletor de travessias iniciado (a cada ${intervalo/1000}s).`);
let falhas=0,coletas=0,ultimaColeta=null,ultimaDeuCerto=null;
// Os nomes lastRunAt, lastOk e failures são lidos pela aba Bastidores do aplicativo.
const sinal=iniciarSinalDeVida(pool,{processo:nomeDoProcesso('collector'),papel:'collector',info:()=>({lastRunAt:ultimaColeta,lastOk:ultimaDeuCerto,failures:falhas})});
while(!encerrando){
  try{const {gravadas,mudancas}=await coletor.executar();falhas=0;ultimaDeuCerto=true;console.log(`Coleta: ${gravadas} travessias, ${mudancas} com mudança.`);}
  catch(erro){falhas++;ultimaDeuCerto=false;console.error('Coleta falhou; último dado preservado:',erro.message);}
  ultimaColeta=new Date().toISOString();
  if(++coletas%60===0)await pool.query("DELETE FROM crossing_snapshots WHERE collected_at<now()-interval '30 days'").catch(()=>{});
  // Em falha, espera mais a cada tentativa (até 10 min) para não sobrecarregar a fonte oficial.
  const espera=Math.min(intervalo*2**Math.min(falhas,4),600_000);
  for(let esperado=0;esperado<espera&&!encerrando;esperado+=500)await new Promise(resolver=>setTimeout(resolver,500));
}
sinal.parar();await pool.end();
