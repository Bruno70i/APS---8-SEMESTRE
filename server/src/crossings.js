import { z } from 'zod';

// Travessias da Baixada Santista acompanhadas pelo ViaAgora. Terminais e rotas tirados das linhas de balsa do
// OpenStreetMap (vias 48452393, 496378138 e 466892175); a rota vai do ponto A ao ponto B.
export const crossings=[
  {id:'santos-guaruja',name:'Santos ⇄ Guarujá',kind:'veiculos',
    pointA:{name:'Santos',address:'Praça Gago Coutinho, 525 - Ponta da Praia',latitude:-23.98692,longitude:-46.29379},
    pointB:{name:'Guarujá',address:'Av. Ademar de Barros, 3300',latitude:-23.98832,longitude:-46.28944},
    route:[[-23.98692,-46.29379],[-23.98832,-46.28944]]},
  {id:'santos-vicente-de-carvalho',name:'Santos ⇄ Vicente de Carvalho',kind:'pedestres',
    pointA:{name:'Santos',address:'Praça da República - Centro',latitude:-23.93171,longitude:-46.32451},
    pointB:{name:'Vicente de Carvalho',address:'Av. Thiago Ferreira',latitude:-23.93964,longitude:-46.30827},
    route:[[-23.93171,-46.32451],[-23.93075,-46.32423],[-23.93068,-46.32406],[-23.93152,-46.31825],[-23.93313,-46.31383],
      [-23.93512,-46.31194],[-23.94001,-46.31023],[-23.93963,-46.30835],[-23.93964,-46.30827]]},
  {id:'bertioga-guaruja',name:'Bertioga ⇄ Guarujá',kind:'veiculos',
    pointA:{name:'Bertioga',address:'Av. Henrique Costabile, 50 - Jardim Veleiros',latitude:-23.85639,longitude:-46.14465},
    pointB:{name:'Guarujá',address:'Estrada Guarujá-Bertioga, km 29',latitude:-23.86028,longitude:-46.14337},
    route:[[-23.85639,-46.14465],[-23.86028,-46.14337]]},
];
export const crossingIds=crossings.map(c=>c.id);
export const crossingById=id=>crossings.find(c=>c.id===id);

export const SITE_URL='https://semil.sp.gov.br/travessias/';
export const DEFAULT_FEED_URL='https://drivetis-travessias.brazilsouth.cloudapp.azure.com:1444/TravessiaService/PT';
const headers={'User-Agent':'Mozilla/5.0 (compatible; ViaAgora-APS/1.0; projeto academico)','Accept':'text/html,application/xhtml+xml,application/json','Accept-Language':'pt-BR,pt;q=0.9'};

export const slug=value=>value.normalize('NFD').replace(/[̀-ͯ]/g,'').toLowerCase().trim().replace(/[^a-z0-9]+/g,'-').replace(/^-|-$/g,'');
// Aviso fixo do 0800 que aparece em todas as travessias; não informa nada sobre a situação atual.
const genericNotice=/^(o\s+)?0800[-\d\s]*.{0,40}24 horas/i;
const stripHtml=value=>(value??'').replace(/<[^>]*>/g,' ').replace(/&nbsp;/g,' ').replace(/\s+/g,' ').trim();

// Página da SEMIL -> <script ...travessia-service...> -> variável getInfo (ignora linhas comentadas).
export function findServiceScript(html,base=SITE_URL){
  const src=html.match(/<script[^>]+src="([^"]*travessia-service[^"]*)"/i)?.[1];
  return src?new URL(src,base).toString():null;
}
export function findFeedBase(script){
  for(const line of script.split(/\r?\n/)){
    if(line.trim().startsWith('//'))continue;
    const url=line.match(/getInfo\s*=\s*["'](https?:\/\/[^"']+)["']/)?.[1];
    if(url)return url.endsWith('/')?url:url+'/';
  }
  return null;
}

async function download(fetchImpl,url,type){
  const response=await fetchImpl(url,{headers,signal:AbortSignal.timeout(15000)});
  if(!response.ok)throw new Error(`Fonte respondeu ${response.status} em ${new URL(url).host}`);
  return type==='json'?response.json():response.text();
}
export async function discoverFeedUrl(fetchImpl=fetch){
  const html=await download(fetchImpl,SITE_URL,'text');
  const script=findServiceScript(html);
  if(!script)throw new Error('Script do painel não encontrado na página.');
  const base=findFeedBase(await download(fetchImpl,script,'text'));
  if(!base)throw new Error('Endereço do serviço não encontrado no script.');
  return base+'PT';
}

const feedItem=z.object({
  TravessiaPontoA:z.string(),TravessiaPontoB:z.string(),
  EsperaPontoA:z.number().int().min(0).max(1440),EsperaPontoB:z.number().int().min(0).max(1440),
  NumEmbarca:z.number().int().min(0).max(50),EmbarcaAtivo:z.boolean(),
  ClimaA:z.string().nullish(),ClimaB:z.string().nullish(),DiaNoite:z.string().nullish(),
  AvisoUsuarioApp:z.string().nullish(),AvisoUsuario:z.string().nullish(),DataConsulta:z.string().nullish(),
}).passthrough();

// "10/28/2026 9:16:41 PM" no horário de Brasília (UTC-3, sem horário de verão).
export function parseSourceTime(value){
  const m=value?.match(/^(\d{1,2})\/(\d{1,2})\/(\d{4}) (\d{1,2}):(\d{2}):(\d{2}) (AM|PM)$/);
  if(!m)return null;
  let hour=Number(m[4])%12;if(m[7]==='PM')hour+=12;
  const date=new Date(Date.UTC(Number(m[3]),Number(m[1])-1,Number(m[2]),hour+3,Number(m[5]),Number(m[6])));
  return Number.isNaN(date.getTime())?null:date.toISOString();
}

const notice=value=>{const text=stripHtml(value).slice(0,400);return text&&!genericNotice.test(text)?text:null;};
// O painel nem sempre zera as embarcações numa paralisação; às vezes só avisa no texto. Restrições parciais
// (ex.: só veículos pesados) e avisos de retomada não contam como travessia parada.
const stopWords=/\b(interrompid[ao]s?|paralisad[ao]s?|paralisacao|suspens[ao]s?|interditad[ao]s?|fechad[ao]s?|sem operacao)\b/;
const resumed=/(retomad|normaliz|restabelecid|liberad|reabert|voltou a operar)/;
const partial=/(pesad|caminh|carga)/;
// Frases que falam da travessia inteira valem mesmo se o aviso também citar veículos pesados.
const wholeStop=/(travessia (esta |foi )?(interrompid|paralisad|suspens|fechad)|operacao (esta |foi )?(suspens|interrompid|paralisad)|sem operacao|paralisacao (total|geral))/;
export function isStopNotice(text){
  const plain=(text??'').normalize('NFD').replace(/[̀-ͯ]/g,'').toLowerCase();
  if(resumed.test(plain))return false;
  return wholeStop.test(plain)||(stopWords.test(plain)&&!partial.test(plain));
}
export function parseFeed(data){
  if(!Array.isArray(data))throw new Error(typeof data==='string'?`Fonte recusou a consulta: ${data}`:'Formato inesperado da fonte.');
  const result=[];
  for(const raw of data){
    const item=feedItem.safeParse(raw);
    if(!item.success)continue;
    const d=item.data,id=slug(`${d.TravessiaPontoA} ${d.TravessiaPontoB}`);
    if(!crossingById(id))continue;
    const text=notice(d.AvisoUsuarioApp||d.AvisoUsuario);
    result.push({crossingId:id,waitA:d.EsperaPontoA,waitB:d.EsperaPontoB,boats:d.NumEmbarca,operating:d.EmbarcaAtivo&&d.NumEmbarca>0&&!isStopNotice(text),
      weatherA:stripHtml(d.ClimaA)||null,weatherB:stripHtml(d.ClimaB)||null,period:stripHtml(d.DiaNoite)||null,
      notice:text,sourceTime:parseSourceTime(d.DataConsulta)});
  }
  return result;
}

export async function fetchOfficial(fetchImpl=fetch,feedUrl=DEFAULT_FEED_URL){
  return parseFeed(await download(fetchImpl,feedUrl,'json'));
}

