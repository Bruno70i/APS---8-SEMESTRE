'use strict';
// Site do ViaAgora: troca de abas (Sistema e Rodar local), explicação de cada componente da arquitetura
// e o botão "Iniciar fluxo", que percorre o caminho de um relato pelo sistema distribuído.

const abas = [...document.querySelectorAll('[data-aba]')];
const paineis = ['inicio', ...abas.map(aba => aba.dataset.aba)];
// Endereços antigos do site (#projeto, #arquitetura...) continuam abrindo a aba certa.
const nomesAntigos = { projeto: 'inicio', apresentacao: 'inicio', arquitetura: 'sistema', avaliacao: 'inicio' };

// A tela inicial não tem aba própria: sem aba marcada, só o botão "Iniciar fluxo".
function selecionarAba(nome, focar = false) {
  nome = nomesAntigos[nome] || nome;
  if (!paineis.includes(nome)) nome = 'inicio';
  if (nome !== 'sistema') pararFluxo();
  paineis.forEach(painel => { document.getElementById(`painel-${painel}`).hidden = painel !== nome; });
  abas.forEach((aba, indice) => {
    const selecionada = aba.dataset.aba === nome;
    aba.setAttribute('aria-selected', String(selecionada));
    aba.tabIndex = selecionada || (nome === 'inicio' && indice === 0) ? 0 : -1;
    if (selecionada && focar) aba.focus();
  });
  history.replaceState(null, '', nome === 'inicio' ? location.pathname : `#${nome}`);
}

// Teclado: setas, Home e End trocam de aba (padrão de acessibilidade para abas).
abas.forEach((aba, indice) => {
  aba.addEventListener('click', () => selecionarAba(aba.dataset.aba));
  aba.addEventListener('keydown', evento => {
    let proxima;
    if (evento.key === 'ArrowRight') proxima = (indice + 1) % abas.length;
    if (evento.key === 'ArrowLeft') proxima = (indice + abas.length - 1) % abas.length;
    if (evento.key === 'Home') proxima = 0;
    if (evento.key === 'End') proxima = abas.length - 1;
    if (proxima !== undefined) {
      evento.preventDefault();
      selecionarAba(abas[proxima].dataset.aba, true);
    }
  });
});

document.querySelector('.identity').addEventListener('click', evento => {
  evento.preventDefault();
  selecionarAba('inicio');
});
window.addEventListener('hashchange', () => selecionarAba(location.hash.slice(1)));

// O que cada componente do diagrama faz no sistema distribuído.
const descricoes = {
  app: ['ViaAgora.', 'Consulta as travessias, envia relatos e busca o estado atualizado após cada aviso.'],
  nginx: ['Nginx.', 'Recebe o acesso HTTPS e distribui as requisições e conexões entre as duas réplicas da API.'],
  api1: ['API 1.', 'Valida e grava os relatos, consulta o banco e mantém as conexões dos seus clientes.'],
  api2: ['API 2.', 'Executa o mesmo código da API 1 e compartilha os dados persistidos no PostgreSQL.'],
  banco: ['PostgreSQL.', 'Salva o relato ou a leitura e seu evento na mesma transação, usando uma outbox.'],
  worker: ['Worker.', 'Publica os eventos pendentes da outbox e aguarda a confirmação do RabbitMQ.'],
  broker: ['RabbitMQ.', 'Entrega uma cópia de cada evento para cada réplica da API por uma exchange fanout.'],
  avisos: ['APIs → celular.', 'Cada réplica avisa seus clientes por WebSocket, e o app consulta os dados novamente.'],
  coletor: ['Coletor.', 'Consulta o painel SEMIL-SP periodicamente e grava as leituras e suas alterações no PostgreSQL.']
};
const componentes = [...document.querySelectorAll('[data-componente]')];
const legenda = document.getElementById('descricao-componente');

// Destaca um componente no diagrama e mostra a explicação dele embaixo.
function mostrar(componente, prefixo = '') {
  componentes.forEach(outro => outro.setAttribute('aria-pressed', String(outro.dataset.componente === componente)));
  const [nome, descricao] = descricoes[componente];
  const titulo = document.createElement('strong');
  titulo.textContent = prefixo + nome;
  legenda.replaceChildren(titulo, ` ${descricao}`);
}
componentes.forEach(componente => {
  componente.addEventListener('click', () => { pararFluxo(); mostrar(componente.dataset.componente); });
});

// "Iniciar fluxo": abre o Sistema e percorre o caminho de um relato, um processo a cada 2,2 segundos.
const fluxo = ['app', 'nginx', 'api1', 'banco', 'worker', 'broker', 'avisos', 'app'];
let temporizadorFluxo = null;
function pararFluxo() { clearTimeout(temporizadorFluxo); temporizadorFluxo = null; }
function rodarFluxo(passo = 0) {
  mostrar(fluxo[passo], `${passo + 1}/${fluxo.length} · `);
  if (passo + 1 < fluxo.length) temporizadorFluxo = setTimeout(() => rodarFluxo(passo + 1), 2200);
  else temporizadorFluxo = null;
}
document.querySelector('[data-iniciar-fluxo]').addEventListener('click', () => {
  selecionarAba('sistema');
  document.getElementById('painel-sistema').scrollIntoView({ behavior: 'smooth', block: 'start' });
  pararFluxo();
  rodarFluxo();
});

selecionarAba(location.hash.slice(1));
