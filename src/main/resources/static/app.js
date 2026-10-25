const citySelect = document.querySelector('#city');
const notice = document.querySelector('#notice');

async function loadCities() {
  try {
    const response = await fetch('/api/cities');
    if (!response.ok) throw new Error('Не удалось получить города');
    const cities = await response.json();
    citySelect.replaceChildren();
    for (const city of cities) {
      citySelect.add(new Option(city.name, city.slug));
    }
    citySelect.disabled = cities.length === 0;
    notice.textContent = cities.length ? '' : 'Список городов пока пуст.';
  } catch {
    citySelect.replaceChildren(new Option('Города недоступны', ''));
    citySelect.disabled = true;
    notice.textContent = 'Не удалось загрузить города. Попробуйте обновить страницу позже.';
  }
}

loadCities();
