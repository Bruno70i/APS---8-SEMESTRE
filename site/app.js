'use strict';

const tabs = [...document.querySelectorAll('[data-tab]')];
const panels = ['inicio', ...tabs.map(tab => tab.dataset.tab)];
const oldNames = { projeto: 'inicio', apresentacao: 'inicio', arquitetura: 'sistema', avaliacao: 'inicio' };

// A tela inicial não tem aba própria: sem aba marcada, só o botão "Iniciar fluxo".
function selectTab(name, focus = false) {
  name = oldNames[name] || name;
  if (!panels.includes(name)) name = 'inicio';
  if (name !== 'sistema') stopFlow();
  panels.forEach(panel => { document.getElementById(`painel-${panel}`).hidden = panel !== name; });
  tabs.forEach((tab, index) => {
    const selected = tab.dataset.tab === name;
    tab.setAttribute('aria-selected', String(selected));
    tab.tabIndex = selected || (name === 'inicio' && index === 0) ? 0 : -1;
    if (selected && focus) tab.focus();
  });
  history.replaceState(null, '', name === 'inicio' ? location.pathname : `#${name}`);
}

tabs.forEach((tab, index) => {
  tab.addEventListener('click', () => selectTab(tab.dataset.tab));
  tab.addEventListener('keydown', event => {
    let next;
    if (event.key === 'ArrowRight') next = (index + 1) % tabs.length;
    if (event.key === 'ArrowLeft') next = (index + tabs.length - 1) % tabs.length;
    if (event.key === 'Home') next = 0;
    if (event.key === 'End') next = tabs.length - 1;
    if (next !== undefined) {
      event.preventDefault();
      selectTab(tabs[next].dataset.tab, true);
    }
  });
});

document.querySelector('.identity').addEventListener('click', event => {
  event.preventDefault();
  selectTab('inicio');
});
window.addEventListener('hashchange', () => selectTab(location.hash.slice(1)));

const descriptions = {
  app: ['ViaAgora.', 'Consulta as travessias, envia relatos e busca o estado atualizado após cada aviso.'],
  nginx: ['Nginx.', 'Recebe o acesso HTTPS e distribui as requisições e conexões entre as duas réplicas da API.'],
  api1: ['API 1.', 'Valida e grava os relatos, consulta o banco e mantém as conexões dos seus clientes.'],
  api2: ['API 2.', 'Executa o mesmo código da API 1 e compartilha os dados persistidos no PostgreSQL.'],
  db: ['PostgreSQL.', 'Salva o relato ou a leitura e seu evento na mesma transação, usando uma outbox.'],
  worker: ['Worker.', 'Publica os eventos pendentes da outbox e aguarda a confirmação do RabbitMQ.'],
  broker: ['RabbitMQ.', 'Entrega uma cópia de cada evento para cada réplica da API por uma exchange fanout.'],
  events: ['APIs → celular.', 'Cada réplica avisa seus clientes por WebSocket, e o app consulta os dados novamente.'],
  collector: ['Coletor.', 'Consulta o painel SEMIL-SP periodicamente e grava as leituras e suas alterações no PostgreSQL.']
};
const components = [...document.querySelectorAll('[data-node]')];
const caption = document.getElementById('component-description');

function show(node, prefix = '') {
  components.forEach(other => other.setAttribute('aria-pressed', String(other.dataset.node === node)));
  const [name, description] = descriptions[node];
  const title = document.createElement('strong');
  title.textContent = prefix + name;
  caption.replaceChildren(title, ` ${description}`);
}
components.forEach(component => {
  component.addEventListener('click', () => { stopFlow(); show(component.dataset.node); });
});

// "Iniciar fluxo": abre o Sistema e percorre o caminho de um relato, um processo por vez.
const flow = ['app', 'nginx', 'api1', 'db', 'worker', 'broker', 'events', 'app'];
let flowTimer = null;
function stopFlow() { clearTimeout(flowTimer); flowTimer = null; }
function runFlow(step = 0) {
  show(flow[step], `${step + 1}/${flow.length} · `);
  if (step + 1 < flow.length) flowTimer = setTimeout(() => runFlow(step + 1), 2200);
  else flowTimer = null;
}
document.querySelector('[data-start-flow]').addEventListener('click', () => {
  selectTab('sistema');
  document.getElementById('painel-sistema').scrollIntoView({ behavior: 'smooth', block: 'start' });
  stopFlow();
  runFlow();
});

selectTab(location.hash.slice(1));
