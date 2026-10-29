// Processo da API: cada réplica (api-1 e api-2) roda este arquivo com um NODE_NAME diferente.
import {montarApp} from './app.js';
import {criarPool,migrar} from './db.js';
import {urlRabbit} from './broker.js';
import {nomeDoProcesso,iniciarSinalDeVida} from './heartbeat.js';
const pool=criarPool();
await migrar(pool);
const replica=nomeDoProcesso('api');
const proxiesConfiaveis=process.env.TRUST_PROXY;
const app=await montarApp({pool,urlBroker:urlRabbit(),replica,confiarProxy:proxiesConfiaveis?proxiesConfiaveis.split(',').map(v=>v.trim()):false});
await app.listen({host:'0.0.0.0',port:Number(process.env.PORT||4310)});
// Os nomes clients e broker são lidos pela aba Bastidores do aplicativo.
const sinal=iniciarSinalDeVida(pool,{processo:replica,papel:'api',info:()=>({clients:app.tempoReal.celulares,broker:app.tempoReal.conectado})});
console.log(`ViaAgora: API ${replica} iniciada na porta`,app.server.address().port);
let encerrando=false;
async function encerrar(){if(encerrando)return;encerrando=true;sinal.parar();await app.close();await pool.end();}
process.on('SIGINT',encerrar);process.on('SIGTERM',encerrar);
