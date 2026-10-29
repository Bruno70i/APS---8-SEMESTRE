import { discoverFeedUrl,fetchOfficial,DEFAULT_FEED_URL } from './crossings.js';
import { recordSnapshots,recordFailure } from './domain.js';

// Uma coleta: descobre o endereço do serviço varrendo o site (só quando ainda não sabe ou quando a leitura falha),
// lê os dados, normaliza e grava. Falhas ficam registradas e o último dado bom continua disponível na API.
export function createCollector({pool,fetchImpl=fetch,feedUrl=null,log=console}){
  let url=feedUrl;
  async function resolveUrl(force){
    if(url&&!force)return url;
    try{url=await discoverFeedUrl(fetchImpl);log.info?.('Serviço das travessias encontrado no site:',url);}
    catch(error){log.warn?.('Varredura do site falhou, usando endereço conhecido:',error.message);url=url||DEFAULT_FEED_URL;}
    return url;
  }
  return {
    get feedUrl(){return url;},
    async run(){
      try{
        let statuses;
        try{statuses=await fetchOfficial(fetchImpl,await resolveUrl(false));}
        catch{statuses=await fetchOfficial(fetchImpl,await resolveUrl(true));}
        if(!statuses.length)throw new Error('Nenhuma travessia da Baixada Santista na resposta da fonte.');
        return await recordSnapshots(pool,statuses,url);
      }catch(error){await recordFailure(pool,error.message);throw error;}
    },
  };
}
