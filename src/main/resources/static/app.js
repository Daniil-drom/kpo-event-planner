const $ = (id) => document.getElementById(id);
let page = 0;
let request = 0;
let detailRequest = 0;
async function json(url) {
  const response = await fetch(url);
  if (!response.ok) throw new Error(response.status === 400 ? 'Проверьте даты и фильтры.' : 'Не удалось загрузить данные. Попробуйте ещё раз.');
  return response.json();
}
function element(tag, text) {
  const node = document.createElement(tag);
  node.textContent = text;
  return node;
}
function time(value, zone, hasTime = true) {
  if (value && !hasTime) return new Date(value).toLocaleDateString('ru-RU', {timeZone: zone, dateStyle: 'short'});
  return value ? new Date(value).toLocaleString('ru-RU', {timeZone: zone, dateStyle: 'short', timeStyle: 'short'}) : 'Время уточняется';
}
function source(url) {
  const link = element('a', 'Подробнее у организатора');
  try {
    const parsed = new URL(url);
    if (['http:', 'https:'].includes(parsed.protocol) && !parsed.username && !parsed.password) link.href = parsed.href;
  } catch { /* Некорректная внешняя ссылка остаётся текстом. */ }
  link.target = '_blank';
  link.rel = 'noopener noreferrer';
  return link;
}
async function showDetail(id) {
  const current = ++detailRequest;
  const dialog = $('detail');
  $('detail-content').replaceChildren(element('p', 'Загрузка…'));
  if (!dialog.open) dialog.showModal();
  try {
    const event = await json('/api/events/' + id);
    if (current !== detailRequest) return;
    const nodes = [element('h2', event.title), element('p', event.description || 'Описание отсутствует.'),
      element('p', [event.place, event.address].filter(Boolean).join(', ')),
      element('p', event.isFree ? 'Бесплатно' : event.price || 'Стоимость уточняется'),
      element('p', event.ageRestriction || ''), element('p', 'Время указано для города ' + event.city + '.')];
    for (const session of event.sessions) nodes.push(element('p',
      time(session.startAt, event.timezone, session.hasStartTime) + ' — ' + (session.isEndless ? 'без даты окончания' : time(session.endAt, event.timezone, session.hasEndTime))));
    if (!event.isActive) nodes.push(element('p', 'Событие больше не доступно в актуальном каталоге.'));
    nodes.push(source(event.siteUrl));
    $('detail-content').replaceChildren(...nodes);
  } catch (error) {
    if (current === detailRequest) $('detail-content').replaceChildren(element('p', error.message));
  }
}
async function loadEvents() {
  const current = ++request;
  $('notice').textContent = 'Загрузка мероприятий…';
  $('events').replaceChildren();
  $('prev').disabled = $('next').disabled = true;
  $('page').textContent = '';
  const params = new URLSearchParams({city: $('city').value, page, size: 12});
  for (const key of ['category', 'dateFrom', 'dateTo']) if ($(key).value) params.set(key, $(key).value);
  if ($('free').checked) params.set('free', 'true');
  try {
    const data = await json('/api/events?' + params);
    if (current !== request) return;
    for (const event of data.items) {
      const card = element('article', '');
      card.className = 'event-card';
      const button = element('button', 'Открыть событие');
      button.addEventListener('click', () => showDetail(event.id));
      card.append(element('h3', event.title), element('p', time(event.nextSession, event.timezone, event.nextSessionHasTime)),
        element('p', event.place || 'Место уточняется'), element('p', event.isFree ? 'Бесплатно' : event.price || 'Стоимость уточняется'),
        button, source(event.siteUrl));
      $('events').append(card);
    }
    $('notice').textContent = data.totalItems ? 'Найдено: ' + data.totalItems : 'Мероприятий нет. Измените фильтры или повторите поиск позже: каталог мог ещё не обновиться.';
    $('page').textContent = data.totalPages ? (page + 1) + ' / ' + data.totalPages : '';
    $('prev').disabled = page === 0;
    $('next').disabled = page + 1 >= data.totalPages;
    refreshCategories();
  } catch (error) { if (current === request) $('notice').textContent = error.message; }
}
$('filters').addEventListener('submit', (event) => { event.preventDefault(); page = 0; loadEvents(); });
$('city').addEventListener('change', () => { page = 0; loadEvents(); });
$('prev').addEventListener('click', () => { page--; loadEvents(); });
$('next').addEventListener('click', () => { page++; loadEvents(); });
$('close-detail').addEventListener('click', () => $('detail').close());
$('detail').addEventListener('close', () => { detailRequest++; });
async function refreshCategories() {
  try {
    const categories = await json('/api/categories');
    const selected = $('category').value;
    $('category').replaceChildren(new Option('Все категории', ''), ...categories.map((c) => new Option(c.name, c.slug)));
    $('category').value = selected;
    $('category').disabled = false;
  } catch { /* Каталог остаётся доступным при ошибке загрузки категорий. */ }
}
async function start() {
  try {
    const cities = await json('/api/cities');
    $('city').replaceChildren(...cities.map((city) => new Option(city.name, city.slug)));
    $('city').disabled = cities.length === 0;
    if (cities.some((city) => city.slug === 'helsinki')) $('city').value = 'helsinki';
    if (cities.length) await loadEvents();
    else $('notice').textContent = 'Города пока не добавлены.';
  } catch (error) { $('notice').textContent = error.message; }
}
start();
