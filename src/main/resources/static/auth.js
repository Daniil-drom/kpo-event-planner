(() => {
  const byId = (id) => document.getElementById(id);
  const dialog = byId('auth-dialog');
  const form = byId('auth-form');
  let mode = 'login';
  let busy = false;
  let accountRequest = 0;
  let account = null;
  let accountKnown = false;

  class ApiError extends Error {
    constructor(status, message, data) { super(message); this.status = status; this.data = data; }
  }

  async function request(url, options = {}) {
    let response;
    try { response = await fetch(url, {credentials: 'same-origin', cache: 'no-store', ...options}); }
    catch { throw new Error('Сервер недоступен. Повторите попытку позже.'); }
    const data = response.status === 204 ? null : await response.json().catch(() => ({}));
    if (!response.ok) throw new ApiError(response.status, data?.message || data?.detail || 'Не удалось выполнить действие. Повторите позже.', data);
    return data;
  }

  async function mutate(url, method, data, asForm = false) {
    // Obtain a fresh token before every action: login and logout rotate or clear it.
    const csrf = await request('/api/auth/csrf');
    const headers = {[csrf.headerName]: csrf.token};
    if (!asForm && data != null) headers['Content-Type'] = 'application/json';
    return request(url, {
      method, headers,
      body: data == null ? undefined : asForm ? new URLSearchParams(data) : JSON.stringify(data)
    });
  }

  const post = (url, data, asForm = false) => mutate(url, 'POST', data, asForm);

  function renderAccount(user) {
    const changed = !accountKnown || account?.id !== user?.id;
    account = user;
    accountKnown = true;
    byId('account-status').textContent = user ? user.email : 'Гость';
    byId('open-login').hidden = byId('open-register').hidden = Boolean(user);
    byId('logout').hidden = !user;
    if (changed) document.dispatchEvent(new CustomEvent('planner:account-changed', {detail: user}));
  }

  async function refreshAccount() {
    const current = ++accountRequest;
    try {
      const user = await request('/api/auth/me');
      if (current === accountRequest) renderAccount(user);
    } catch (error) {
      if (current !== accountRequest) return;
      if (error.status === 401) renderAccount(null);
      else {
        byId('account-status').textContent = 'Вход не проверен';
        byId('account-notice').textContent = error.message;
      }
    }
  }

  function setBusy(value) {
    busy = value;
    for (const id of ['open-login', 'open-register', 'logout', 'close-auth', 'auth-submit',
      'switch-auth', 'auth-email', 'auth-password', 'auth-confirm']) byId(id).disabled = value;
    form.setAttribute('aria-busy', String(value));
  }

  function setMode(value) {
    mode = value;
    const registration = mode === 'register';
    byId('auth-title').textContent = registration ? 'Регистрация' : 'Вход';
    byId('auth-submit').textContent = registration ? 'Зарегистрироваться' : 'Войти';
    byId('switch-auth').textContent = registration ? 'Уже есть аккаунт — войти' : 'Создать аккаунт';
    byId('confirm-field').hidden = !registration;
    byId('auth-confirm').required = registration;
    byId('auth-password').minLength = registration ? 8 : 1;
    byId('auth-password').autocomplete = registration ? 'new-password' : 'current-password';
    byId('password-hint').textContent = registration ? 'Не менее 8 символов.' : 'Пароль, указанный при регистрации.';
    byId('auth-password').value = byId('auth-confirm').value = '';
    byId('auth-confirm').setCustomValidity('');
    byId('auth-notice').textContent = '';
  }

  function open(value) {
    if (busy) return;
    setMode(value);
    byId('account-notice').textContent = '';
    dialog.showModal();
    byId('auth-email').focus();
  }

  byId('open-login').addEventListener('click', () => open('login'));
  byId('open-register').addEventListener('click', () => open('register'));
  byId('switch-auth').addEventListener('click', () => {
    if (!busy) setMode(mode === 'login' ? 'register' : 'login');
  });
  byId('close-auth').addEventListener('click', () => { if (!busy) dialog.close(); });
  dialog.addEventListener('cancel', (event) => { if (busy) event.preventDefault(); });
  dialog.addEventListener('close', () => {
    byId('auth-password').value = byId('auth-confirm').value = '';
    byId('auth-confirm').setCustomValidity('');
    byId('auth-notice').textContent = '';
  });
  for (const id of ['auth-password', 'auth-confirm'])
    byId(id).addEventListener('input', () => byId('auth-confirm').setCustomValidity(''));

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (busy) return;
    const registration = mode === 'register';
    byId('auth-confirm').setCustomValidity(registration && byId('auth-password').value !== byId('auth-confirm').value
      ? 'Пароли не совпадают.' : '');
    if (!form.reportValidity()) return;
    const credentials = {email: byId('auth-email').value.trim(), password: byId('auth-password').value};
    setBusy(true);
    byId('auth-notice').textContent = registration ? 'Создание аккаунта…' : 'Вход…';
    try {
      const user = await post('/api/auth/' + (registration ? 'register' : 'login'), credentials, !registration);
      if (registration) {
        setMode('login');
        byId('auth-email').value = user.email;
        byId('auth-notice').textContent = 'Аккаунт создан. Теперь войдите с указанным паролем.';
      } else {
        accountRequest++;
        renderAccount(user);
        dialog.close();
        byId('account-notice').textContent = 'Вы вошли в аккаунт.';
      }
    } catch (error) { byId('auth-notice').textContent = error.message; }
    finally {
      setBusy(false);
      if (dialog.open) byId('auth-password').focus();
    }
  });

  byId('logout').addEventListener('click', async () => {
    if (busy) return;
    setBusy(true);
    byId('account-notice').textContent = 'Выход…';
    try {
      await post('/api/auth/logout');
      accountRequest++;
      renderAccount(null);
      byId('account-notice').textContent = 'Вы вышли из аккаунта.';
    } catch (error) { byId('account-notice').textContent = error.message; }
    finally { setBusy(false); }
  });

  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && !busy) refreshAccount();
  });
  window.plannerAuth = Object.freeze({
    get user() { return account; }, request, mutate, refresh: refreshAccount, openLogin: () => open('login')
  });
  refreshAccount();
})();
