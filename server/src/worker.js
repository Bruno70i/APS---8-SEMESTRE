// Processo worker: a cada 0,75 s publica no RabbitMQ os eventos que estão esperando na outbox.
import {criarPool,migrar} from './db.js';
import {publicarLote} from './broker.js';
import {nomeDoProcesso,iniciarSinalDeVida} from './heartbeat.js';
const pool=criarPool();await migrar(pool);let encerrando=false;
process.on('SIGINT',()=>{encerrando=true;});process.on('SIGTERM',()=>{encerrando=true;});
let publicados=0,ultimaPublicacao=null,voltas=0;
const sinal=iniciarSinalDeVida(pool,{processo:nomeDoProcesso('worker'),papel:'worker',info:()=>({published:publicados,lastPublishAt:ultimaPublicacao})});
console.log('ViaAgora: worker de eventos iniciado.');
while(!encerrando){
  // Se o broker estiver fora do ar, a publicação falha e os eventos continuam guardados na outbox.
  try{const quantidade=await publicarLote(pool);if(quantidade){publicados+=quantidade;ultimaPublicacao=new Date().toISOString();console.log('Eventos publicados:',quantidade);}}
  catch(erro){console.error('Publicação adiada; dados preservados:',erro.code||erro.name);}
  // Limpeza simples, uma vez por hora: eventos já publicados há mais de 7 dias.
  if(++voltas%4800===0)await pool.query("DELETE FROM outbox_events WHERE published_at<now()-interval '7 days'; DELETE FROM event_deliveries WHERE delivered_at<now()-interval '7 days'").catch(()=>{});
  await new Promise(resolver=>setTimeout(resolver,750));
}
sinal.parar();await pool.end();
