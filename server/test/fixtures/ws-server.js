import pg from 'pg';
import {montarApp} from '../../src/app.js';
const pool=new pg.Pool({connectionString:process.env.TEST_DATABASE_URL});
const app=await montarApp({pool,urlBroker:process.env.RABBITMQ_URL});
await app.listen({host:'127.0.0.1',port:0});
process.send({port:app.server.address().port});
process.on('message',async message=>{if(message==='stop'){await app.close();await pool.end();process.exit(0);}});
