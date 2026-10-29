// API HTTP do ViaAgora. As duas réplicas (api-1 e api-2) rodam este mesmo código e usam o mesmo banco;
// o Nginx reparte os celulares entre elas. As rotas e os nomes dos campos são o contrato com o aplicativo.
import Fastify from 'fastify';
import limiteDeRequisicoes from '@fastify/rate-limit';
import { randomBytes,randomUUID } from 'node:crypto';
import { z } from 'zod';
import { gerarHash,ErroHttp,esquemaRelato,categorias,salvarRelato,encerrarRelato,listarTravessias,historicoTravessia,estadoFonte } from './domain.js';
import { idsTravessias } from './crossings.js';
import { paraRelato } from './db.js';
import { ligarTempoReal } from './broker.js';
import { estadoSistema } from './system.js';

const uuid=z.string().uuid();
// Filtros aceitos em GET /v1/reports e em GET /v1/crossings/:id/history.
const filtrosRelatos=z.object({crossing:z.enum(idsTravessias).optional(),category:z.enum(categorias).optional(),status:z.enum(['active','resolved']).optional(),
  sinceMinutes:z.coerce.number().int().min(1).max(10080).default(180),limit:z.coerce.number().int().min(1).max(500).default(200)}).strict();
const filtrosHistorico=z.object({days:z.coerce.number().int().min(1).max(30).default(7)}).strict();

// confiarProxy: atrás do Nginx, o limite de requisições deve contar o IP real de cada usuário, não o do proxy.
export async function montarApp({pool,estadoBroker=()=>false,urlBroker=null,replica='api',confiarProxy=false}){
  const app=Fastify({bodyLimit:8192,logger:false,trustProxy:confiarProxy});
  // Canal ao vivo (WebSocket) ligado ao RabbitMQ. Nos testes sem broker ele fica desligado.
  const tempoReal=urlBroker?ligarTempoReal(app,urlBroker,{replica,pool}):null;
  app.decorate('tempoReal',tempoReal);
  const brokerConectado=()=>tempoReal?tempoReal.conectado:estadoBroker();
  await app.register(limiteDeRequisicoes,{max:300,timeWindow:'1 minute'});
  // Todo erro vira uma mensagem curta em português; falhas internas (ex.: banco fora do ar) respondem 503.
  app.setErrorHandler((erro,requisicao,resposta)=>{
    const codigo=erro.statusCode || (erro instanceof z.ZodError?400:503);
    if(codigo>=500)console.error('Requisição falhou:',erro.message);
    resposta.code(codigo).send({message:codigo>=500?'Serviço temporariamente indisponível. Tente novamente.':erro instanceof z.ZodError?'Confira travessia, lado, tipo, espera e coordenadas.':erro.message});
  });
  // Descobre qual aparelho fez o pedido pelo token. O banco guarda só o hash do token.
  async function autenticar(requisicao){
    const token=requisicao.headers.authorization?.match(/^Bearer ([A-Za-z0-9_-]{43})$/)?.[1];
    if(!token)throw new ErroHttp(401,'Identifique este aparelho antes de publicar.');
    const resultado=await pool.query('SELECT id FROM participants WHERE token_hash=$1',[gerarHash(token)]);
    if(!resultado.rowCount)throw new ErroHttp(401,'Participação inválida. Reconecte o aplicativo.');
    return resultado.rows[0].id;
  }
  // Participação anônima: o aparelho recebe um token aleatório uma única vez, sem cadastro.
  app.post('/v1/participants',{config:{rateLimit:{max:15,timeWindow:'1 minute'}}},async(requisicao,resposta)=>{
    const token=randomBytes(32).toString('base64url'),participantId=randomUUID();
    await pool.query('INSERT INTO participants(id,token_hash) VALUES($1,$2)',[participantId,gerarHash(token)]);
    return resposta.code(201).send({participantId,token});
  });
  app.get('/v1/crossings',async()=>({crossings:await listarTravessias(pool),source:await estadoFonte(pool),serverTime:new Date().toISOString()}));
  app.get('/v1/crossings/:id/history',async requisicao=>{
    const {days}=filtrosHistorico.parse(requisicao.query);
    return historicoTravessia(pool,z.string().max(60).parse(requisicao.params.id),days);
  });
  app.get('/v1/reports',async requisicao=>{
    const filtros=filtrosRelatos.parse(requisicao.query),valores=[filtros.sinceMinutes],condicoes=['created_at>now()-make_interval(mins=>$1)'];
    for(const [filtro,coluna] of [['crossing','crossing_id'],['category','category'],['status','status']])if(filtros[filtro]){valores.push(filtros[filtro]);condicoes.push(`${coluna}=$${valores.length}`);}
    valores.push(filtros.limit);
    const linhas=await pool.query(`SELECT * FROM reports WHERE ${condicoes.join(' AND ')} ORDER BY created_at DESC LIMIT $${valores.length}`,valores);
    return {reports:linhas.rows.map(paraRelato),serverTime:new Date().toISOString()};
  });
  // Novo relato: exige o token e uma chave de idempotência (o mesmo envio repetido não duplica o relato).
  app.post('/v1/reports',async(requisicao,resposta)=>{
    const autorId=await autenticar(requisicao),entrada=esquemaRelato.parse(requisicao.body);
    const chave=uuid.safeParse(requisicao.headers['idempotency-key']);
    if(!chave.success)throw new ErroHttp(400,'Informe uma chave de envio válida.');
    const resultado=await salvarRelato(pool,autorId,entrada,chave.data,replica);
    return resposta.code(resultado.reenvio?200:201).send({report:resultado.relato});
  });
  app.patch('/v1/reports/:id/resolve',async requisicao=>{
    const autorId=await autenticar(requisicao),id=uuid.parse(requisicao.params.id);
    return {report:await encerrarRelato(pool,autorId,id,replica)};
  });
  app.get('/v1/system',async()=>estadoSistema(pool,{replica,brokerConectado:brokerConectado()}));
  // Saúde do serviço: só o banco e o broker derrubam o código HTTP; o coletor é informativo.
  app.get('/health',async(requisicao,resposta)=>{
    let banco='up',fonte=null;
    try{await pool.query('SELECT 1');fonte=await estadoFonte(pool);}catch{banco='down';}
    const broker=brokerConectado()?'up':'down',ok=banco==='up'&&broker==='up';
    return resposta.code(ok?200:503).send({status:ok?'ok':'degraded',services:{database:banco,broker,collector:fonte?.available?'up':'down'},name:'ViaAgora'});
  });
  return app;
}
