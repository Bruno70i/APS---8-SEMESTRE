// Travessias acompanhadas e leitura da fonte oficial (web scraping do site da Acquavias, que opera as
// Travessias Litorâneas; o antigo serviço JSON do painel da SEMIL-SP fica como reserva).
import { z } from 'zod';

// Travessias da Baixada Santista acompanhadas pelo ViaAgora. Terminais e rotas tirados das linhas de balsa do
// OpenStreetMap (vias 48452393, 496378138 e 466892175); a rota vai do ponto A ao ponto B.
// Os nomes dos campos (name, pointA, route...) são enviados ao aplicativo e fazem parte da API.
export const travessias=[
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
export const idsTravessias=travessias.map(t=>t.id);
export const travessiaPorId=id=>travessias.find(t=>t.id===id);

// Site oficial das Travessias Litorâneas. Cada travessia tem uma página própria (ex.: /santos-guaruja/).
export const ENDERECO_SITE='https://acquavias.com.br/';
export const enderecoPagina=id=>new URL(`${id}/`,ENDERECO_SITE).toString();
// Serviço JSON do antigo painel da SEMIL-SP, usado como reserva se o site da Acquavias falhar.
export const ENDERECO_SERVICO_PADRAO='https://drivetis-travessias.brazilsouth.cloudapp.azure.com:1444/TravessiaService/PT';
const cabecalhos={'User-Agent':'Mozilla/5.0 (compatible; ViaAgora-APS/1.0; projeto academico)','Accept':'text/html,application/xhtml+xml,application/json','Accept-Language':'pt-BR,pt;q=0.9'};

// "Santos Guarujá" -> "santos-guaruja": sem acentos, minúsculo e com hífens.
export const gerarSlug=valor=>valor.normalize('NFD').replace(/[̀-ͯ]/g,'').toLowerCase().trim().replace(/[^a-z0-9]+/g,'-').replace(/^-|-$/g,'');
// Aviso fixo do 0800 que aparece em todas as travessias; não informa nada sobre a situação atual.
const avisoGenerico=/^(o\s+)?0800[-\d\s]*.{0,40}24 horas/i;
const removerHtml=valor=>(valor??'').replace(/<[^>]*>/g,' ').replace(/&nbsp;/g,' ').replace(/\s+/g,' ').trim();

// Baixa uma página ou um JSON, desistindo depois de 15 segundos.
async function baixar(buscarImpl,endereco,tipo){
  const resposta=await buscarImpl(endereco,{headers:cabecalhos,signal:AbortSignal.timeout(15000)});
  if(!resposta.ok)throw new Error(`Fonte respondeu ${resposta.status} em ${new URL(endereco).host}`);
  return tipo==='json'?resposta.json():resposta.text();
}

// ---- Site da Acquavias ----
// "30 min" -> 30; "1h 10min" -> 70; "1 h" -> 60. Texto sem número (ex.: "-") -> null.
export function lerMinutos(texto){
  const simples=removerHtml(texto).toLowerCase();
  const horas=simples.match(/(\d+)\s*h/)?.[1],minutos=simples.match(/(\d+)\s*min/)?.[1]??(horas?null:simples.match(/^(\d+)$/)?.[1]);
  if(horas==null&&minutos==null)return null;
  return Number(horas??0)*60+Number(minutos??0);
}
// Texto alternativo do ícone do clima: "Parcialmente Nublado — Dia" -> clima "Parcialmente Nublado", período "Dia".
function lerClima(alt){
  const [clima,periodo]=removerHtml(alt).split(/\s+[—–-]\s+/);
  return {clima:clima||null,periodo:periodo||null};
}
const textosDaClasse=(html,classe)=>[...html.matchAll(new RegExp(`class="${classe}"[^>]*>([\\s\\S]*?)</(?:span|div)>`,'g'))].map(m=>removerHtml(m[1]));
const altsDaClasse=(html,classe)=>[...html.matchAll(new RegExp(`class="${classe}"[^>]*>\\s*<img[^>]*?alt="([^"]*)"`,'g'))].map(m=>m[1]);

// Página de uma travessia na Acquavias -> leitura no mesmo formato do painel antigo.
// A barra do topo (aqv-topbar) traz esperas, embarcações e clima de todas as travessias; o corpo da página
// traz a espera e o clima de cada lado e o aviso ao usuário.
export function lerPaginaAcquavias(html,id){
  const item=[...html.matchAll(/<a\s[^>]*class="aqv-topbar__item"[\s\S]*?<\/a>/g)].map(m=>m[0])
    .find(bloco=>bloco.match(/href="([^"]*)"/)?.[1]?.replace(/^https?:\/\/[^/]+/,'')===`/${id}/`);
  const esperasCorpo=textosDaClasse(html,'aqv-tempo-dinamico__valor').map(lerMinutos);
  const esperasTopo=item?[...item.matchAll(/<span>([^<]*)<\/span>/g)].map(m=>lerMinutos(m[1])).filter(v=>v!=null):[];
  const [waitA,waitB]=esperasCorpo.length>=2&&esperasCorpo.every(v=>v!=null)?esperasCorpo:esperasTopo;
  const boats=item?Number.parseInt(item.match(/aqv-topbar__quantidade">\s*(\d+)/)?.[1],10):null;
  const climas=altsDaClasse(html,'aqv-clima-destaque').map(lerClima);
  const climaTopo=lerClima(item?.match(/aqv-topbar__icone-clima"[^>]*?alt="([^"]*)"/)?.[1]??'');
  const aviso=limparAviso(textosDaClasse(html,'aqv-aviso-usuario').find(Boolean));
  const leitura={crossingId:id,waitA,waitB,boats,
    operating:boats>0&&!ehAvisoDeParada(aviso),
    weatherA:(climas[0]??climaTopo).clima,weatherB:(climas[1]??climaTopo).clima,period:(climas[0]??climaTopo).periodo,
    notice:aviso,sourceTime:null};
  return leituraAcquavias.safeParse(leitura).success?leitura:null;
}
const leituraAcquavias=z.object({waitA:z.number().int().min(0).max(1440),waitB:z.number().int().min(0).max(1440),boats:z.number().int().min(0).max(50)}).passthrough();

// Lê as páginas das três travessias ao mesmo tempo. Se só uma falhar, as outras continuam valendo.
export async function buscarAcquavias(buscarImpl=fetch){
  const resultados=await Promise.allSettled(idsTravessias.map(async id=>{
    const leitura=lerPaginaAcquavias(await baixar(buscarImpl,enderecoPagina(id),'text'),id);
    if(!leitura)throw new Error(`Página de ${id} sem os tempos de espera.`);
    return leitura;
  }));
  const leituras=resultados.filter(r=>r.status==='fulfilled').map(r=>r.value);
  if(!leituras.length)throw new Error(resultados[0].reason?.message??'Site da Acquavias sem dados.');
  return leituras;
}

// ---- Reserva: serviço JSON do antigo painel da SEMIL-SP ----

// Formato de cada item do JSON do painel oficial (validado com Zod; itens fora do formato são ignorados).
const itemPainel=z.object({
  TravessiaPontoA:z.string(),TravessiaPontoB:z.string(),
  EsperaPontoA:z.number().int().min(0).max(1440),EsperaPontoB:z.number().int().min(0).max(1440),
  NumEmbarca:z.number().int().min(0).max(50),EmbarcaAtivo:z.boolean(),
  ClimaA:z.string().nullish(),ClimaB:z.string().nullish(),DiaNoite:z.string().nullish(),
  AvisoUsuarioApp:z.string().nullish(),AvisoUsuario:z.string().nullish(),DataConsulta:z.string().nullish(),
}).passthrough();

// "10/28/2026 9:16:41 PM" no horário de Brasília (UTC-3, sem horário de verão) -> data ISO em UTC.
export function lerHorarioFonte(valor){
  const m=valor?.match(/^(\d{1,2})\/(\d{1,2})\/(\d{4}) (\d{1,2}):(\d{2}):(\d{2}) (AM|PM)$/);
  if(!m)return null;
  let hora=Number(m[4])%12;if(m[7]==='PM')hora+=12;
  const data=new Date(Date.UTC(Number(m[3]),Number(m[1])-1,Number(m[2]),hora+3,Number(m[5]),Number(m[6])));
  return Number.isNaN(data.getTime())?null:data.toISOString();
}

const limparAviso=valor=>{const texto=removerHtml(valor).slice(0,400);return texto&&!avisoGenerico.test(texto)?texto:null;};
// O painel nem sempre zera as embarcações numa paralisação; às vezes só avisa no texto. Restrições parciais
// (ex.: só veículos pesados) e avisos de retomada não contam como travessia parada.
const palavrasDeParada=/\b(interrompid[ao]s?|paralisad[ao]s?|paralisacao|suspens[ao]s?|interditad[ao]s?|fechad[ao]s?|sem operacao)\b/;
const retomada=/(retomad|normaliz|restabelecid|liberad|reabert|voltou a operar)/;
const parcial=/(pesad|caminh|carga)/;
// Frases que falam da travessia inteira valem mesmo se o aviso também citar veículos pesados.
const paradaTotal=/(travessia (esta |foi )?(interrompid|paralisad|suspens|fechad)|operacao (esta |foi )?(suspens|interrompid|paralisad)|sem operacao|paralisacao (total|geral))/;
export function ehAvisoDeParada(texto){
  const simples=(texto??'').normalize('NFD').replace(/[̀-ͯ]/g,'').toLowerCase();
  if(retomada.test(simples))return false;
  return paradaTotal.test(simples)||(palavrasDeParada.test(simples)&&!parcial.test(simples));
}
// JSON do painel -> leituras das três travessias da Baixada Santista (as demais travessias do estado são ignoradas).
export function lerPainel(dados){
  if(!Array.isArray(dados))throw new Error(typeof dados==='string'?`Fonte recusou a consulta: ${dados}`:'Formato inesperado da fonte.');
  const leituras=[];
  for(const bruto of dados){
    const item=itemPainel.safeParse(bruto);
    if(!item.success)continue;
    const d=item.data,id=gerarSlug(`${d.TravessiaPontoA} ${d.TravessiaPontoB}`);
    if(!travessiaPorId(id))continue;
    const aviso=limparAviso(d.AvisoUsuarioApp||d.AvisoUsuario);
    leituras.push({crossingId:id,waitA:d.EsperaPontoA,waitB:d.EsperaPontoB,boats:d.NumEmbarca,operating:d.EmbarcaAtivo&&d.NumEmbarca>0&&!ehAvisoDeParada(aviso),
      weatherA:removerHtml(d.ClimaA)||null,weatherB:removerHtml(d.ClimaB)||null,period:removerHtml(d.DiaNoite)||null,
      notice:aviso,sourceTime:lerHorarioFonte(d.DataConsulta)});
  }
  return leituras;
}

export async function buscarPainelOficial(buscarImpl=fetch,enderecoServico=ENDERECO_SERVICO_PADRAO){
  return lerPainel(await baixar(buscarImpl,enderecoServico,'json'));
}
