import amqp from 'amqplib';
import { WebSocketServer,WebSocket } from 'ws';

const exchange='viaagora.changes';
export const rabbitUrl=()=>process.env.RABBITMQ_URL || 'amqp://viaagora:viaagora_local_only@127.0.0.1:15679';
const safelyClose=async resource=>{try{await resource?.close();}catch{}};

export function attachRealtime(app,url,{replica='api',pool=null}={}){
  const wss=new WebSocketServer({noServer:true,maxPayload:1024});
  let connection,channel,timer,stopping=false,connected=false;
  const broadcast=event=>{for(const client of wss.clients){if(client.readyState===WebSocket.OPEN){if(client.bufferedAmount>1024*1024)client.terminate();else client.send(JSON.stringify(event));}}};
  app.server.on('upgrade',(request,socket,head)=>{
    if(request.url!=='/v1/events'){socket.destroy();return;}
    wss.handleUpgrade(request,socket,head,client=>wss.emit('connection',client,request));
  });
  wss.on('connection',client=>{
    client.alive=true;
    client.on('error',()=>{client.terminate();});
    client.on('pong',()=>{client.alive=true;});
    client.send(JSON.stringify({type:'ready',connected,replica}));
  });
  const heartbeat=setInterval(()=>{for(const client of wss.clients){if(!client.alive)client.terminate();else{client.alive=false;client.ping();}}},15000);
  heartbeat.unref();
  function retry(){
    if(connected&&!stopping)broadcast({type:'broker.status',connected:false});
    connected=false;
    if(!stopping&&!timer){timer=setTimeout(()=>{timer=null;void connect();},1500);timer.unref();}
  }
  async function connect(){
    try{
      connection=await amqp.connect(url,{timeout:3000});
      connection.on('error',()=>{});connection.on('close',retry);
      if(stopping){await safelyClose(connection);return;}
      channel=await connection.createChannel();channel.on('error',()=>{});
      await channel.assertExchange(exchange,'fanout',{durable:true});
      const queue=await channel.assertQueue('',{exclusive:true,autoDelete:true});
      await channel.bindQueue(queue.queue,exchange,'');
      await channel.consume(queue.queue,message=>{
        if(!message)return;
        try{
          const event=JSON.parse(message.content.toString());
          // Cada réplica carimba quem entregou e quando, e registra a entrega para a tela Bastidores.
          broadcast({...event,via:replica,deliveredAt:new Date().toISOString()});channel.ack(message);
          if(pool&&event.eventId)pool.query('INSERT INTO event_deliveries(event_id,replica,clients) VALUES($1,$2,$3) ON CONFLICT DO NOTHING',
            [event.eventId,replica,wss.clients.size]).catch(()=>{});
        }catch{channel.nack(message,false,false);}
      });
      connected=true;
      broadcast({type:'resync',connected:true});
    }catch{await safelyClose(connection);retry();}
  }
  app.addHook('onReady',async()=>{await connect();});
  app.addHook('preClose',async()=>{
    stopping=true;connected=false;clearTimeout(timer);clearInterval(heartbeat);
    for(const client of wss.clients)client.terminate();wss.close();
    await safelyClose(channel);await safelyClose(connection);
  });
  return {get connected(){return connected;},get clients(){return wss.clients.size;}};
}

export async function publishBatch(pool,url=rabbitUrl()){
  const client=await pool.connect();let connection,channel;
  try{
    await client.query('BEGIN');
    const pending=await client.query('SELECT id,payload FROM outbox_events WHERE published_at IS NULL ORDER BY created_at LIMIT 25 FOR UPDATE SKIP LOCKED');
    if(!pending.rowCount){await client.query('COMMIT');return 0;}
    connection=await amqp.connect(url,{timeout:2000});connection.on('error',()=>{});
    channel=await connection.createConfirmChannel();channel.on('error',()=>{});
    await channel.assertExchange(exchange,'fanout',{durable:true});
    for(const row of pending.rows){
      // O consumidor pode receber antes de a confirmação chegar ao worker. Medimos
      // a saída com o relógio do banco, mas só encerramos a pendência após confirmar.
      const publishedAt=(await client.query('SELECT clock_timestamp() AS sent_at')).rows[0].sent_at;
      await new Promise((resolve,reject)=>channel.publish(exchange,'',Buffer.from(JSON.stringify(row.payload)),{persistent:true,messageId:row.id,contentType:'application/json'},error=>error?reject(error):resolve()));
      await client.query('UPDATE outbox_events SET published_at=$2,attempts=attempts+1 WHERE id=$1',[row.id,publishedAt]);
    }
    await client.query('COMMIT');return pending.rowCount;
  }catch(error){await client.query('ROLLBACK');throw error;}
  finally{await safelyClose(channel);await safelyClose(connection);client.release();}
}
