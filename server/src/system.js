// Estado do sistema distribuído para a tela Bastidores: quem está vivo, fila de eventos e o caminho dos últimos eventos.
export const ALIVE_MS=15_000;

export async function systemState(pool,{replica,brokerConnected}){
  const nodes=(await pool.query(`SELECT node,role,info,started_at,last_seen,(extract(epoch FROM now()-last_seen)*1000)::int AS age_ms
    FROM node_heartbeats WHERE last_seen>now()-interval '30 minutes' ORDER BY role,node`)).rows;
  const outbox=(await pool.query(`SELECT count(*) FILTER (WHERE published_at IS NULL)::int AS pending,
    count(*) FILTER (WHERE created_at>now()-interval '1 hour')::int AS last_hour FROM outbox_events`)).rows[0];
  const counters=Object.fromEntries((await pool.query('SELECT name,value::int AS value FROM system_counters')).rows.map(r=>[r.name,r.value]));
  const collections=(await pool.query(`SELECT count(*) FILTER (WHERE collected_at>now()-interval '24 hours')::int AS last24h,count(*)::int AS total FROM crossing_snapshots`)).rows[0];
  const events=(await pool.query(`SELECT o.id,o.payload->>'type' AS type,o.payload->>'origin' AS origin,o.payload->>'crossingId' AS crossing_id,o.created_at,o.published_at,
      COALESCE(json_agg(json_build_object('replica',d.replica,'clients',d.clients,'deliveredAt',d.delivered_at) ORDER BY d.replica) FILTER (WHERE d.replica IS NOT NULL),'[]') AS deliveries
    FROM (SELECT * FROM outbox_events ORDER BY created_at DESC LIMIT 8) o LEFT JOIN event_deliveries d ON d.event_id=o.id
    GROUP BY o.id,o.payload,o.created_at,o.published_at ORDER BY o.created_at DESC`)).rows;
  const apis=nodes.filter(n=>n.role==='api'&&n.age_ms<ALIVE_MS);
  return {
    servedBy:replica,serverTime:new Date().toISOString(),aliveAfterMs:ALIVE_MS,
    nodes:nodes.map(n=>({node:n.node,role:n.role,alive:n.age_ms<ALIVE_MS,ageMs:n.age_ms,startedAt:n.started_at.toISOString(),info:n.info})),
    services:{database:'up',broker:brokerConnected||apis.some(n=>n.info?.broker)?'up':'down'},
    outbox:{pending:outbox.pending,lastHour:outbox.last_hour},
    counters:{idempotentReplays:counters.idempotent_replays??0},
    collections,
    events:events.map(e=>({eventId:e.id,type:e.type,origin:e.origin,crossingId:e.crossing_id,createdAt:e.created_at.toISOString(),
      publishedAt:e.published_at?.toISOString()??null,
      deliveries:e.deliveries.map(d=>({replica:d.replica,clients:d.clients,deliveredAt:new Date(d.deliveredAt).toISOString()}))})),
  };
}
