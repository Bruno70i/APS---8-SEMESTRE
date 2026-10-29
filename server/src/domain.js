// Regras de negócio do servidor: relatos, leituras do painel oficial e o padrão outbox.
// Toda escrita acontece numa transação que grava o dado E o evento juntos: ou salva os dois, ou nenhum.
import { createHash, randomUUID } from 'node:crypto';
import { z } from 'zod';
import { paraRelato,paraLeitura } from './db.js';
import { travessias,idsTravessias,travessiaPorId } from './crossings.js';

export const categorias=['fila','embarque_lento','interrompida','clima','acidente'];
export const LEITURA_ATRASADA_MS=10*60_000; // leitura com mais de 10 minutos é mostrada como "dado atrasado"

// Formato aceito para um novo relato (os nomes dos campos são o contrato com o aplicativo).
export const esquemaRelato=z.object({
  crossingId:z.enum(idsTravessias), side:z.enum(['A','B']), category:z.enum(categorias),
  waitMinutes:z.number().int().min(0).max(600).nullable().default(null),
  description:z.string().trim().max(400).default(''),
  latitude:z.number().min(-90).max(90), longitude:z.number().min(-180).max(180),
}).strict();
export const gerarHash=valor=>createHash('sha256').update(valor).digest('hex');
// Erro com código HTTP (401, 403, 404, 409...) que a API devolve ao aplicativo com a mensagem.
export class ErroHttp extends Error {constructor(statusCode,mensagem){super(mensagem);this.statusCode=statusCode;}}

// Executa "operacao" dentro de uma transação do PostgreSQL (BEGIN ... COMMIT, ou ROLLBACK se der erro).
async function transacao(pool,operacao){
  const conexao=await pool.connect();
  try{await conexao.query('BEGIN');const resultado=await operacao(conexao);await conexao.query('COMMIT');return resultado;}
  catch(erro){await conexao.query('ROLLBACK');throw erro;}
  finally{conexao.release();}
}
// Grava um evento na outbox, na mesma transação do dado. O worker publica depois no RabbitMQ.
// "origem" diz quem gerou o evento (api-1, api-2 ou collector), para a aba Bastidores desenhar o caminho.
async function registrarEvento(conexao,dados,origem){
  const evento={eventId:randomUUID(),...dados,origin:origem};
  await conexao.query('INSERT INTO outbox_events(id,payload) VALUES($1,$2)',[evento.eventId,evento]);
}

// Salva um relato e o evento "report.created" juntos. A chave de idempotência (única por participante)
// faz o reenvio do mesmo relato devolver o relato existente em vez de criar outro.
export async function salvarRelato(pool,autorId,entrada,chave,origem='api'){
  const hashDoPedido=gerarHash(JSON.stringify(entrada));
  return transacao(pool,async conexao=>{
    const inserido=await conexao.query(`INSERT INTO reports(id,author_id,crossing_id,side,category,wait_minutes,description,latitude,longitude,idempotency_key,request_hash)
      VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11) ON CONFLICT(author_id,idempotency_key) DO NOTHING RETURNING *`,
    [randomUUID(),autorId,entrada.crossingId,entrada.side,entrada.category,entrada.waitMinutes,entrada.description,entrada.latitude,entrada.longitude,chave,hashDoPedido]);
    if(inserido.rowCount){
      const relato=paraRelato(inserido.rows[0]);
      await registrarEvento(conexao,{type:'report.created',reportId:relato.id,crossingId:relato.crossingId,version:relato.version},origem);
      return {relato,reenvio:false};
    }
    const existente=(await conexao.query('SELECT * FROM reports WHERE author_id=$1 AND idempotency_key=$2',[autorId,chave])).rows[0];
    if(existente.request_hash!==hashDoPedido)throw new ErroHttp(409,'Esta chave já foi usada para outro relato.');
    // Reenvio do mesmo relato (idempotência): nada é duplicado, só contamos para mostrar na aba Bastidores.
    await conexao.query(`INSERT INTO system_counters(name,value) VALUES('idempotent_replays',1) ON CONFLICT(name) DO UPDATE SET value=system_counters.value+1`);
    return {relato:paraRelato(existente),reenvio:true};
  });
}
// Encerra um relato (só quem publicou pode). Encerrar de novo não gera outro evento.
export async function encerrarRelato(pool,autorId,id,origem='api'){
  return transacao(pool,async conexao=>{
    const resultado=await conexao.query('SELECT * FROM reports WHERE id=$1 FOR UPDATE',[id]);
    if(!resultado.rowCount)throw new ErroHttp(404,'Relato não encontrado.');
    const linha=resultado.rows[0];
    if(linha.author_id!==autorId)throw new ErroHttp(403,'Somente quem publicou pode encerrar este relato.');
    if(linha.status==='resolved')return paraRelato(linha);
    const atualizado=await conexao.query("UPDATE reports SET status='resolved',version=version+1,updated_at=now() WHERE id=$1 RETURNING *",[id]);
    const relato=paraRelato(atualizado.rows[0]);
    await registrarEvento(conexao,{type:'report.resolved',reportId:relato.id,crossingId:relato.crossingId,version:relato.version},origem);
    return relato;
  });
}

const mudou=(anterior,nova)=>!anterior||['wait_a','wait_b','boats','operating','weather_a','weather_b','notice'].some(campo=>anterior[campo]!==nova[campo]);
// Grava uma leitura de cada travessia. O evento só sai quando algo visível mudou, para não acordar os celulares à toa.
export async function gravarLeituras(pool,leituras,enderecoServico=null){
  return transacao(pool,async conexao=>{
    let mudancas=0;
    for(const l of leituras){
      const nova={wait_a:l.waitA,wait_b:l.waitB,boats:l.boats,operating:l.operating,weather_a:l.weatherA,weather_b:l.weatherB,notice:l.notice};
      const anterior=(await conexao.query('SELECT * FROM crossing_snapshots WHERE crossing_id=$1 ORDER BY collected_at DESC LIMIT 1',[l.crossingId])).rows[0];
      await conexao.query(`INSERT INTO crossing_snapshots(crossing_id,wait_a,wait_b,boats,operating,weather_a,weather_b,period,notice,source,source_time)
        VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,'oficial',$10)`,[l.crossingId,l.waitA,l.waitB,l.boats,l.operating,l.weatherA,l.weatherB,l.period,l.notice,l.sourceTime]);
      if(mudou(anterior,nova)){mudancas++;await registrarEvento(conexao,{type:'crossing.updated',crossingId:l.crossingId},'collector');}
    }
    const estado=(await conexao.query('SELECT failures,last_success_at FROM collector_state WHERE id=1 FOR UPDATE')).rows[0];
    await conexao.query(`INSERT INTO collector_state(id,mode,feed_url,last_attempt_at,last_success_at,last_error,failures) VALUES(1,'oficial',$1,now(),now(),NULL,0)
      ON CONFLICT(id) DO UPDATE SET feed_url=COALESCE($1,collector_state.feed_url),last_attempt_at=now(),last_success_at=now(),last_error=NULL,failures=0`,[enderecoServico]);
    // A fonte oficial voltou depois de falhar: avisa os celulares.
    if(estado?.failures>0)await registrarEvento(conexao,{type:'source.status',available:true},'collector');
    return {gravadas:leituras.length,mudancas};
  });
}
// Registra uma falha da coleta. Na primeira falha seguida, avisa os celulares que a fonte caiu.
export async function registrarFalha(pool,mensagem){
  return transacao(pool,async conexao=>{
    const estado=(await conexao.query(`INSERT INTO collector_state(id,mode,last_attempt_at,last_error,failures) VALUES(1,'oficial',now(),$1,1)
      ON CONFLICT(id) DO UPDATE SET last_attempt_at=now(),last_error=$1,failures=collector_state.failures+1 RETURNING failures`,[String(mensagem).slice(0,300)])).rows[0];
    if(estado.failures===1)await registrarEvento(conexao,{type:'source.status',available:false},'collector');
    return estado.failures;
  });
}

// Situação da fonte oficial: disponível se a última coleta deu certo há menos de 10 minutos.
export async function estadoFonte(pool){
  const linha=(await pool.query('SELECT * FROM collector_state WHERE id=1')).rows[0];
  if(!linha)return {mode:null,available:false,lastSuccessAt:null,lastError:'Coletor ainda não executou.'};
  const recente=linha.last_success_at&&Date.now()-linha.last_success_at.getTime()<LEITURA_ATRASADA_MS;
  return {mode:linha.mode,available:Boolean(recente)&&linha.failures===0,lastSuccessAt:linha.last_success_at?.toISOString()??null,lastError:linha.last_error};
}
// As três travessias com a última leitura oficial, a estimativa da comunidade e a contagem de relatos.
export async function listarTravessias(pool){
  const ultimas=(await pool.query('SELECT DISTINCT ON (crossing_id) * FROM crossing_snapshots ORDER BY crossing_id,collected_at DESC')).rows;
  // Estimativa da comunidade: mediana das esperas informadas nos últimos 45 minutos, por lado.
  const comunidade=(await pool.query(`SELECT crossing_id,side,percentile_cont(0.5) WITHIN GROUP (ORDER BY wait_minutes) AS median,count(*)::int AS reports
    FROM reports WHERE status='active' AND wait_minutes IS NOT NULL AND created_at>now()-interval '45 minutes' GROUP BY crossing_id,side`)).rows;
  // Relatos de "travessia parada" dos últimos 45 minutos sinalizam paralisação antes de o painel oficial mudar.
  const ativos=(await pool.query(`SELECT crossing_id,count(*)::int AS total,
      count(*) FILTER (WHERE category='interrompida' AND created_at>now()-interval '45 minutes')::int AS stops
    FROM reports WHERE status='active' AND created_at>now()-interval '3 hours' GROUP BY crossing_id`)).rows;
  return travessias.map(t=>{
    const leitura=ultimas.find(l=>l.crossing_id===t.id);
    const relatos=ativos.find(r=>r.crossing_id===t.id);
    const lado=letra=>{const m=comunidade.find(r=>r.crossing_id===t.id&&r.side===letra);return m?{waitMinutes:Math.round(m.median),reports:m.reports}:null;};
    return {...t,status:leitura?paraLeitura(leitura,Date.now()-leitura.collected_at.getTime()>LEITURA_ATRASADA_MS):null,
      community:{A:lado('A'),B:lado('B')},activeReports:relatos?.total??0,stopReports:relatos?.stops??0};
  });
}
// Média de espera por hora do dia nos últimos dias e o melhor horário de cada lado.
export async function historicoTravessia(pool,id,dias){
  if(!travessiaPorId(id))throw new ErroHttp(404,'Travessia não encontrada.');
  const porHora=(await pool.query(`SELECT extract(hour FROM collected_at AT TIME ZONE 'America/Sao_Paulo')::int AS hour,
      round(avg(wait_a))::int AS wait_a,round(avg(wait_b))::int AS wait_b,count(*)::int AS samples
    FROM crossing_snapshots WHERE crossing_id=$1 AND operating AND collected_at>now()-make_interval(days=>$2) GROUP BY 1 ORDER BY 1`,[id,dias])).rows;
  const recentes=(await pool.query(`SELECT to_timestamp(floor(extract(epoch FROM collected_at)/900)*900) AS bucket,round(avg(wait_a))::int AS wait_a,round(avg(wait_b))::int AS wait_b
    FROM crossing_snapshots WHERE crossing_id=$1 AND operating AND collected_at>now()-interval '6 hours' GROUP BY 1 ORDER BY 1`,[id])).rows;
  // Leituras com a travessia parada ficam fora das médias: espera zero com balsa parada não é horário bom.
  // Melhor horário só entre 6h e 22h: recomendar a madrugada não ajuda quem precisa atravessar.
  const uteis=porHora.filter(h=>h.hour>=6&&h.hour<=22);
  const melhor=campo=>uteis.length?uteis.reduce((a,b)=>b[campo]<a[campo]?b:a).hour:null;
  return {crossingId:id,days:dias,
    hourly:porHora.map(h=>({hour:h.hour,waitA:h.wait_a,waitB:h.wait_b,samples:h.samples})),
    recent:recentes.map(r=>({at:r.bucket.toISOString(),waitA:r.wait_a,waitB:r.wait_b})),
    bestHour:{A:melhor('wait_a'),B:melhor('wait_b')}};
}
