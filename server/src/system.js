// Estado do sistema distribuído (rota GET /v1/system), usado pela aba Bastidores:
// quais processos estão vivos, quantos eventos esperam na outbox e o caminho dos últimos eventos.
export const VIVO_ATE_MS=15_000; // sem sinal de vida há mais de 15 s, o processo aparece como fora do ar

export async function estadoSistema(pool,{replica,brokerConectado}){
  const processos=(await pool.query(`SELECT node,role,info,started_at,last_seen,(extract(epoch FROM now()-last_seen)*1000)::int AS age_ms
    FROM node_heartbeats WHERE last_seen>now()-interval '30 minutes' ORDER BY role,node`)).rows;
  const outbox=(await pool.query(`SELECT count(*) FILTER (WHERE published_at IS NULL)::int AS pending,
    count(*) FILTER (WHERE created_at>now()-interval '1 hour')::int AS last_hour FROM outbox_events`)).rows[0];
  const contadores=Object.fromEntries((await pool.query('SELECT name,value::int AS value FROM system_counters')).rows.map(linha=>[linha.name,linha.value]));
  const coletas=(await pool.query(`SELECT count(*) FILTER (WHERE collected_at>now()-interval '24 hours')::int AS last24h,count(*)::int AS total FROM crossing_snapshots`)).rows[0];
  // Últimos 8 eventos da outbox com as entregas de cada réplica (quando e para quantos celulares).
  const eventos=(await pool.query(`SELECT o.id,o.payload->>'type' AS type,o.payload->>'origin' AS origin,o.payload->>'crossingId' AS crossing_id,o.created_at,o.published_at,
      COALESCE(json_agg(json_build_object('replica',d.replica,'clients',d.clients,'deliveredAt',d.delivered_at) ORDER BY d.replica) FILTER (WHERE d.replica IS NOT NULL),'[]') AS deliveries
    FROM (SELECT * FROM outbox_events ORDER BY created_at DESC LIMIT 8) o LEFT JOIN event_deliveries d ON d.event_id=o.id
    GROUP BY o.id,o.payload,o.created_at,o.published_at ORDER BY o.created_at DESC`)).rows;
  const apisVivas=processos.filter(p=>p.role==='api'&&p.age_ms<VIVO_ATE_MS);
  // Os nomes dos campos abaixo são o contrato com o aplicativo.
  return {
    servedBy:replica,serverTime:new Date().toISOString(),aliveAfterMs:VIVO_ATE_MS,
    nodes:processos.map(p=>({node:p.node,role:p.role,alive:p.age_ms<VIVO_ATE_MS,ageMs:p.age_ms,startedAt:p.started_at.toISOString(),info:p.info})),
    services:{database:'up',broker:brokerConectado||apisVivas.some(p=>p.info?.broker)?'up':'down'},
    outbox:{pending:outbox.pending,lastHour:outbox.last_hour},
    counters:{idempotentReplays:contadores.idempotent_replays??0},
    collections:coletas,
    events:eventos.map(e=>({eventId:e.id,type:e.type,origin:e.origin,crossingId:e.crossing_id,createdAt:e.created_at.toISOString(),
      publishedAt:e.published_at?.toISOString()??null,
      deliveries:e.deliveries.map(d=>({replica:d.replica,clients:d.clients,deliveredAt:new Date(d.deliveredAt).toISOString()}))})),
  };
}
