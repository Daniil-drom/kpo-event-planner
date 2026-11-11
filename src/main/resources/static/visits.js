(() => {
  const byId = (id) => document.getElementById(id);
  const auth = window.plannerAuth;
  const dialog = byId('visit-dialog');
  const form = byId('visit-form');
  const statuses = {WANT_TO_VISIT: 'Хочу посетить', PLANNED: 'Запланировано', VISITED: 'Посещено'};
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  let visits = [];
  let ready = false;
  let loading = false;
  let busy = false;
  let accountVersion = 0;
  let listRequest = 0;
  let editorRequest = 0;
  let editing = null;

  byId('schedule-timezone').textContent = 'Время посещений — ' + zone + ' (часовой пояс вашего устройства).';
  byId('visit-timezone').textContent = 'Введите время в часовом поясе устройства: ' + zone + '.';

  function updateCatalogButtons() {
    for (const button of document.querySelectorAll('.visit-action')) button.disabled = busy || !ready;
    for (const button of document.querySelectorAll('[data-save-event]')) {
      const saved = visits.some((visit) => visit.event.id === Number(button.dataset.saveEvent));
      button.textContent = saved ? 'В расписании — изменить' : 'Добавить в расписание';
      button.disabled = busy || Boolean(auth.user && !ready);
    }
  }

  function render() {
    const user = auth.user;
    byId('schedule-tools').hidden = !user;
    byId('schedule-login').hidden = Boolean(user);
    byId('refresh-visits').hidden = !user;
    byId('refresh-visits').disabled = busy || loading;
    byId('visits').replaceChildren();
    if (!user) {
      byId('schedule-notice').textContent = 'Войдите, чтобы составить личное расписание.';
      updateCatalogButtons();
      return;
    }
    const filter = byId('visit-filter').value;
    const shown = visits.filter((visit) => !filter || visit.status === filter);
    let previousDay;
    let group;
    for (const visit of shown) {
      const day = visit.startAt ? new Date(visit.startAt).toLocaleDateString('ru-RU', {dateStyle: 'long'}) : 'Без выбранного времени';
      if (day !== previousDay) {
        byId('visits').append(element('h3', day));
        group = element('div', '');
        group.className = 'event-grid';
        byId('visits').append(group);
        previousDay = day;
      }
      const card = element('article', '');
      card.className = 'event-card';
      const status = element('p', statuses[visit.status]);
      status.className = 'visit-status';
      card.append(element('h4', visit.event.title), status,
        element('p', visit.startAt ? time(visit.startAt) + ' — ' + time(visit.endAt) : 'Время посещения не задано.'),
        element('p', [visit.event.city, visit.event.place, visit.event.address].filter(Boolean).join(', ')));
      if (!visit.event.isActive) {
        const warning = element('p', 'Событие отсутствует в актуальном каталоге. Уточните сведения у организатора.');
        warning.className = 'warning';
        card.append(warning);
      }
      if (visit.hasConflict) {
        const warning = element('p', 'Есть пересечение с другим запланированным посещением.'
          + (visit.conflictAccepted ? ' Сохранение с пересечением подтверждено.' : ' Проверьте время в расписании.'));
        warning.className = 'warning';
        card.append(warning);
      }
      const actions = element('div', '');
      actions.className = 'visit-actions';
      const change = element('button', 'Изменить');
      const remove = element('button', 'Удалить');
      const detail = element('button', 'О событии');
      for (const button of [change, remove, detail]) {
        button.type = 'button';
        button.className = 'visit-action secondary-button';
        button.disabled = busy;
      }
      change.addEventListener('click', () => openEditor(visit.event.id, visit));
      remove.addEventListener('click', () => removeVisit(visit));
      detail.addEventListener('click', () => showDetail(visit.event.id));
      actions.append(change, remove, detail);
      card.append(actions, source(visit.event.siteUrl));
      group.append(card);
    }
    if (ready) byId('schedule-notice').textContent = shown.length ? 'Посещений: ' + shown.length
      : filter ? 'Посещений с выбранным статусом пока нет.' : 'Расписание пока пустое. Добавьте мероприятие из каталога.';
    updateCatalogButtons();
  }

  async function loadVisits(message = '') {
    if (!auth.user) return;
    const current = ++listRequest;
    const version = accountVersion;
    loading = true;
    byId('refresh-visits').disabled = true;
    byId('schedule-notice').textContent = 'Загрузка расписания…';
    try {
      const data = await auth.request('/api/visits');
      if (current !== listRequest || version !== accountVersion) return;
      visits = data;
      ready = true;
      render();
      if (message) byId('schedule-notice').textContent = message;
    } catch (error) {
      if (current !== listRequest || version !== accountVersion) return;
      ready = false;
      byId('schedule-notice').textContent = error.message;
      if (error.status === 401) auth.refresh();
      updateCatalogButtons();
    } finally {
      if (current === listRequest && version === accountVersion) {
        loading = false;
        byId('refresh-visits').disabled = busy;
      }
    }
  }

  function setBusy(value) {
    busy = value;
    for (const id of ['close-visit', 'visit-status', 'visit-start', 'visit-end', 'accept-conflict', 'save-visit'])
      byId(id).disabled = value;
    for (const button of document.querySelectorAll('.visit-action')) button.disabled = value;
    byId('refresh-visits').disabled = value || loading;
    form.setAttribute('aria-busy', String(value));
    updateCatalogButtons();
  }

  function clearConflicts() {
    byId('visit-conflicts').hidden = true;
    byId('visit-conflict-list').replaceChildren();
    byId('accept-conflict').checked = false;
  }

  function requireTime() {
    const required = byId('visit-status').value === 'PLANNED';
    byId('visit-start').required = byId('visit-end').required = required;
  }

  function localInput(value) {
    if (!value) return '';
    const date = new Date(value);
    const pad = (n) => String(n).padStart(2, '0');
    return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate())
      + 'T' + pad(date.getHours()) + ':' + pad(date.getMinutes()) + ':' + pad(date.getSeconds());
  }

  async function openEditor(eventId, visit = null) {
    if (busy) return;
    if (!auth.user) { auth.openLogin(); return; }
    if (!ready) return;
    const current = ++editorRequest;
    const version = accountVersion;
    editing = null;
    form.hidden = true;
    byId('visit-title').textContent = visit ? 'Изменить посещение' : 'Добавить в расписание';
    byId('visit-event-title').textContent = 'Загрузка мероприятия…';
    byId('visit-availability').textContent = '';
    if (!dialog.open) dialog.showModal();
    try {
      const event = await auth.request('/api/events/' + eventId);
      if (current !== editorRequest || version !== accountVersion) return;
      byId('visit-event-title').textContent = event.title;
      if (!event.isActive) {
        byId('visit-availability').textContent = 'Событие отсутствует в актуальном каталоге. Уточните сведения у организатора.';
        if (!visit) return;
      }
      editing = {visit, event};
      form.reset();
      clearConflicts();
      byId('visit-status').value = visit?.status || 'WANT_TO_VISIT';
      byId('visit-start').value = localInput(visit?.startAt);
      byId('visit-end').value = localInput(visit?.endAt);
      requireTime();
      byId('visit-notice').textContent = '';
      form.hidden = false;
      byId('visit-status').focus();
    } catch (error) {
      if (current === editorRequest && version === accountVersion) byId('visit-event-title').textContent = error.message;
    }
  }

  async function removeVisit(visit) {
    if (busy || !window.confirm('Удалить «' + visit.event.title + '» из расписания?')) return;
    const version = accountVersion;
    setBusy(true);
    try {
      await auth.mutate('/api/visits/' + visit.id, 'DELETE');
      if (version === accountVersion) await loadVisits('Посещение удалено из расписания.');
    } catch (error) {
      if (version === accountVersion) {
        byId('schedule-notice').textContent = error.message;
        if (error.status === 401) auth.refresh();
      }
    } finally { if (version === accountVersion) setBusy(false); }
  }

  document.addEventListener('click', (event) => {
    const button = event.target.closest('[data-save-event]');
    if (!button || button.disabled) return;
    const eventId = Number(button.dataset.saveEvent);
    openEditor(eventId, visits.find((visit) => visit.event.id === eventId));
  });
  byId('schedule-login').addEventListener('click', () => auth.openLogin());
  byId('refresh-visits').addEventListener('click', () => loadVisits());
  byId('visit-filter').addEventListener('change', render);
  byId('close-visit').addEventListener('click', () => { if (!busy) dialog.close(); });
  dialog.addEventListener('cancel', (event) => { if (busy) event.preventDefault(); });
  dialog.addEventListener('close', () => { editorRequest++; editing = null; clearConflicts(); form.reset(); });
  for (const id of ['visit-status', 'visit-start', 'visit-end']) byId(id).addEventListener('input', () => {
    requireTime();
    clearConflicts();
    byId('visit-notice').textContent = '';
  });

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (busy || !editing || !form.reportValidity()) return;
    if (!byId('visit-conflicts').hidden && !byId('accept-conflict').checked) {
      byId('visit-notice').textContent = 'Измените время или подтвердите сохранение с пересечением.';
      return;
    }
    const state = editing;
    const version = accountVersion;
    const data = {
      status: byId('visit-status').value,
      startAt: byId('visit-start').value ? new Date(byId('visit-start').value).toISOString() : null,
      endAt: byId('visit-end').value ? new Date(byId('visit-end').value).toISOString() : null,
      acceptConflict: byId('accept-conflict').checked
    };
    if ((data.startAt == null) !== (data.endAt == null) || (data.startAt && data.endAt <= data.startAt)) {
      byId('visit-notice').textContent = 'Укажите оба времени; окончание должно быть позже начала.';
      return;
    }
    if (!state.visit) data.eventId = state.event.id;
    setBusy(true);
    byId('visit-notice').textContent = 'Сохранение…';
    try {
      await auth.mutate(state.visit ? '/api/visits/' + state.visit.id : '/api/visits', state.visit ? 'PATCH' : 'POST', data);
      if (version !== accountVersion) return;
      dialog.close();
      await loadVisits('Посещение сохранено в расписании.');
    } catch (error) {
      if (version !== accountVersion) return;
      byId('visit-notice').textContent = error.message;
      if (error.status === 409 && error.data?.code === 'TIME_CONFLICT') {
        byId('visit-conflict-list').replaceChildren(...error.data.conflicts.map((visit) =>
          element('li', visit.title + ': ' + time(visit.startAt) + ' — ' + time(visit.endAt))));
        byId('accept-conflict').checked = false;
        byId('visit-conflicts').hidden = false;
      }
      if (error.status === 409 && error.data?.code !== 'TIME_CONFLICT') loadVisits();
      if (error.status === 401) auth.refresh();
    } finally { if (version === accountVersion) setBusy(false); }
  });

  document.addEventListener('planner:account-changed', () => {
    accountVersion++;
    listRequest++;
    editorRequest++;
    visits = [];
    ready = loading = false;
    if (dialog.open) dialog.close();
    editing = null;
    setBusy(false);
    byId('visit-filter').value = '';
    render();
    if (auth.user) loadVisits();
  });
  document.addEventListener('planner:catalog-rendered', updateCatalogButtons);
  document.addEventListener('planner:catalog-updated', () => { if (auth.user && !busy) loadVisits(); });
  document.addEventListener('visibilitychange', () => { if (!document.hidden && auth.user && !busy) loadVisits(); });
  render();
  if (auth.user) loadVisits();
})();
