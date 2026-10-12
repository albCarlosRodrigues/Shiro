// Proxy do `ng serve`: encaminha /api para o backend local e injeta o token de desenvolvimento,
// para que o navegador não precise conhecê-lo. As variáveis são definidas por scripts/dev.mjs.
const port = process.env.SQUAD_PORT || '4310';
const token = process.env.SQUAD_AUTH_TOKEN || '';

export default {
  '/api': {
    target: `http://127.0.0.1:${port}`,
    changeOrigin: false,
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  },
};
