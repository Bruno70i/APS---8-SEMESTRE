// Mensageria do sistema distribuído:
//  - publicarLote (usado pelo worker): lê a outbox e publica os eventos no RabbitMQ, com confirmação;
//  - ligarTempoReal (usado por cada réplica da API): recebe os eventos do RabbitMQ e avisa os celulares pelo WebSocket.
import amqp from 'amqplib';
import { WebSocketServer,WebSocket } from 'ws';

const exchange='viaagora.changes'; // exchange do tipo fanout: entrega uma cópia de cada evento a todas as réplicas
export const urlRabbit=()=>process.env.RABBITMQ_URL || 'amqp://viaagora:viaagora_local_only@127.0.0.1:15679';
const fecharComCalma=async recurso=>{try{await recurso?.close();}catch{}};

export function ligarTempoReal(app,url,{replica='api',pool=null}={}){
  const servidorWs=new WebSocketServer({noServer:true,maxPayload:1024});
  let conexao,canal,temporizador,encerrando=false,conectado=false;
  // Envia o evento a todos os celulares ligados nesta réplica (quem está com a fila de envio cheia é desconectado).
  const avisarCelulares=evento=>{for(const celular of servidorWs.clients){if(celular.readyState===WebSocket.OPEN){if(celular.bufferedAmount>1024*1024)celular.terminate();else celular.send(JSON.stringify(evento));}}};
  app.server.on('upgrade',(requisicao,socket,cabeca)=>{
    if(requisicao.url!=='/v1/events'){socket.destroy();return;}
    servidorWs.handleUpgrade(requisicao,socket,cabeca,celular=>servidorWs.emit('connection',celular,requisicao));
  });
  servidorWs.on('connection',celular=>{
    celular.alive=true;
    celular.on('error',()=>{celular.terminate();});
    celular.on('pong',()=>{celular.alive=true;});
    // Primeira mensagem: diz ao celular qual réplica o atende e se o broker está conectado.
    celular.send(JSON.stringify({type:'ready',connected:conectado,replica}));
  });
  // A cada 15 s confere se cada celular ainda responde (ping/pong); quem não respondeu é desligado.
  const verificacao=setInterval(()=>{for(const celular of servidorWs.clients){if(!celular.alive)celular.terminate();else{celular.alive=false;celular.ping();}}},15000);
  verificacao.unref();
  function tentarDeNovo(){
    if(conectado&&!encerrando)avisarCelulares({type:'broker.status',connected:false});
    conectado=false;
    if(!encerrando&&!temporizador){temporizador=setTimeout(()=>{temporizador=null;void conectar();},1500);temporizador.unref();}
  }
  async function conectar(){
    try{
      conexao=await amqp.connect(url,{timeout:3000});
      conexao.on('error',()=>{});conexao.on('close',tentarDeNovo);
      if(encerrando){await fecharComCalma(conexao);return;}
      canal=await conexao.createChannel();canal.on('error',()=>{});
      await canal.assertExchange(exchange,'fanout',{durable:true});
      // Fila exclusiva desta réplica, ligada à exchange fanout: assim TODAS as réplicas recebem cada evento.
      const fila=await canal.assertQueue('',{exclusive:true,autoDelete:true});
      await canal.bindQueue(fila.queue,exchange,'');
      await canal.consume(fila.queue,mensagem=>{
        if(!mensagem)return;
        try{
          const evento=JSON.parse(mensagem.content.toString());
          // Cada réplica carimba quem entregou e quando, e registra a entrega para a aba Bastidores.
          avisarCelulares({...evento,via:replica,deliveredAt:new Date().toISOString()});canal.ack(mensagem);
          if(pool&&evento.eventId)pool.query('INSERT INTO event_deliveries(event_id,replica,clients) VALUES($1,$2,$3) ON CONFLICT DO NOTHING',
            [evento.eventId,replica,servidorWs.clients.size]).catch(()=>{});
        }catch{canal.nack(mensagem,false,false);}
      });
      conectado=true;
      // Reconectou ao broker: pede aos celulares que busquem tudo de novo (algum evento pode ter passado).
      avisarCelulares({type:'resync',connected:true});
    }catch{await fecharComCalma(conexao);tentarDeNovo();}
  }
  app.addHook('onReady',async()=>{await conectar();});
  app.addHook('preClose',async()=>{
    encerrando=true;conectado=false;clearTimeout(temporizador);clearInterval(verificacao);
    for(const celular of servidorWs.clients)celular.terminate();servidorWs.close();
    await fecharComCalma(canal);await fecharComCalma(conexao);
  });
  return {get conectado(){return conectado;},get celulares(){return servidorWs.clients.size;}};
}

// Publica até 25 eventos pendentes da outbox. FOR UPDATE SKIP LOCKED garante que dois workers nunca peguem o mesmo
// evento; o evento só é marcado como publicado depois que o RabbitMQ confirma. Se algo falhar, a transação é
// desfeita e os eventos continuam pendentes para a próxima tentativa.
export async function publicarLote(pool,url=urlRabbit()){
  const conexaoBanco=await pool.connect();let conexao,canal;
  try{
    await conexaoBanco.query('BEGIN');
    const pendentes=await conexaoBanco.query('SELECT id,payload FROM outbox_events WHERE published_at IS NULL ORDER BY created_at LIMIT 25 FOR UPDATE SKIP LOCKED');
    if(!pendentes.rowCount){await conexaoBanco.query('COMMIT');return 0;}
    conexao=await amqp.connect(url,{timeout:2000});conexao.on('error',()=>{});
    canal=await conexao.createConfirmChannel();canal.on('error',()=>{});
    await canal.assertExchange(exchange,'fanout',{durable:true});
    for(const linha of pendentes.rows){
      // O consumidor pode receber antes de a confirmação chegar ao worker. Medimos
      // a saída com o relógio do banco, mas só encerramos a pendência após confirmar.
      const publicadoEm=(await conexaoBanco.query('SELECT clock_timestamp() AS sent_at')).rows[0].sent_at;
      await new Promise((resolver,rejeitar)=>canal.publish(exchange,'',Buffer.from(JSON.stringify(linha.payload)),{persistent:true,messageId:linha.id,contentType:'application/json'},erro=>erro?rejeitar(erro):resolver()));
      await conexaoBanco.query('UPDATE outbox_events SET published_at=$2,attempts=attempts+1 WHERE id=$1',[linha.id,publicadoEm]);
    }
    await conexaoBanco.query('COMMIT');return pendentes.rowCount;
  }catch(erro){await conexaoBanco.query('ROLLBACK');throw erro;}
  finally{await fecharComCalma(canal);await fecharComCalma(conexao);conexaoBanco.release();}
}
