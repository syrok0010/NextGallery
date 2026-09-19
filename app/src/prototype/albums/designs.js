// Пять независимых композиций фото + навигации. Альбомы везде используют модель A.
// Без параметра design исходные варианты A/B/C остаются доступны.
(()=>{
const params=new URLSearchParams(location.search);
const entry=document.createElement('a');entry.href='?design=1';entry.textContent='Новые эскизы: фото и навигация →';entry.style.cssText='display:block;margin-top:20px;color:#705298';document.querySelector('aside').prepend(entry);
if(!params.has('design'))return;
entry.remove();let design=Math.max(1,Math.min(5,Number(params.get('design'))||1)),menu=null,month='Сентябрь',navScroll=0,detailScroll=0;
const study={
1:['Текст вместо панели','Разделы становятся заголовком','Два крупных слова наверху: «Фото» и «Альбомы». Никакой нижней панели. Время задаёт ритм, сетка остаётся близкой к текущей.','Самая мягкая подчистка','Сохраняет плотность и привычные дни. На большом телефоне переключение раздела далеко от большого пальца.'],
2:['Плавающий остров','Фото занимают весь экран','Тёмная плотная сетка на четыре колонки. Небольшой переключатель парит снизу, не растягивается на всю ширину экрана.','Мой фаворит для ежедневного просмотра','Управление под большим пальцем, больше фото в кадре. Остров перекрывает часть ленты при прокрутке; внизу оставлен запас.'],
3:['Боковой корешок','Медиатека как архив','Разделы стоят вдоль левого края, как корешки папок. Две колонки крупных фото и выразительные даты справа от навигации.','Самый необычный из пяти','Навигация не забирает высоту. Но вертикальные подписи читаются медленнее и съедают ширину — это осознанный эксперимент.'],
4:['Раздел в заголовке','Один заголовок. Вся ширина фото.','Тап по «Фото ⌄» открывает выбор раздела. В ленте — мозаика: первый снимок дня крупнее, остальные образуют плотную сетку.','Самый тихий интерфейс','Ничего не висит поверх фото. Переключение раздела требует двух тапов; первый снимок дня получает больше внимания без алгоритмического отбора.'],
5:['Выдвижная библиотека','Лента дней, как фотодневник','Каждый день — отдельная карточка с крупным первым снимком. Нижняя ручка «Библиотека» раскрывает выбор фото и альбомов.','Для неспешного просмотра','Удобная нижняя точка входа и отчётливые границы дней. На экране меньше снимков, путь к старой фотографии длиннее.']};
const groups=[{date:'Сегодня',extra:'13 сентября',ids:[0,3,2,1,4,5,8,9,10]},{date:'Вчера',extra:'12 сентября',ids:[11,12,13,3,0,1]},{date:'10 сентября',extra:'Среда',ids:[14,15,2,4,5,8]},{date:'6 сентября',extra:'Суббота',ids:[9,10,11,12,13,14]}];
const allPhotos=groups.flatMap(g=>g.ids);titles.push('Тихая улица','Вечер в городе','Побережье','Дорога в горы','Городской свет','За городом','На рассвете','Прогулка');
// Вымышленные фотографии, повторное появление в днях — только наполнение макета.
const baseRender=render;variant='A';page='timeline';selected=null;photo=null;
const section=()=>page==='timeline'?'Фото':'Альбомы';
const navButton=(p,label)=>`<button data-dpage="${p}" class="${page===p?'on':''}">${label}</button>`;
const controls=()=>`<button class="iconbutton" data-action="account" aria-label="Аккаунт и обновление">⋯</button>`;
const top=()=>{
if(design===1)return `<div class="toolhead"><span class="wordmark">NEXTGALLERY</span>${controls()}</div><div class="topnav">${navButton('timeline','Фото')}${navButton('albums','Альбомы')}</div>`;
if(design===4)return `<div class="toolhead"><button class="titlemenu" data-action="sections">${section()} ⌄</button>${controls()}</div>`;
return `<div class="toolhead"><span class="wordmark">${design===3?'NG / АРХИВ':'NEXTGALLERY'}</span>${controls()}</div><h2 class="counttitle">${section()}</h2>`;
};
function photoGrid(g){const offset=groups.slice(0,groups.indexOf(g)).reduce((n,g)=>n+g.ids.length,0);return `<div class="photogrid">${g.ids.map((id,n)=>`<button data-dphoto="${offset+n}" aria-label="Открыть фото: ${titles[id]||'Фото'}"><img src="${img(id)}" alt="${titles[id]||'Фото'}" loading="lazy"></button>`).join('')}</div>`}
function timelineHtml(){if(condition==='empty')return '<div class="empty">В медиатеке пока нет фото</div>';let html=`<div class="summaryline">${condition==='partial'?'Показаны доступные фото':'Сентябрь 2026 · телефон и Nextcloud'}</div>`;
if(design===3)html+='<div class="months"><button data-month="Сентябрь">Сен</button><button data-day="2">10 сен</button><button data-day="3">6 сен</button></div>';
return html+groups.map((g,n)=>{const date=month==='Сентябрь'?g.date:`${13-n*2} ${month==='Август'?'августа':'июля'}`;return `<section id="day${n}" class="${design===5?'daycard':''}"><div class="datehead"><strong>${date}</strong><small>${month==='Сентябрь'?g.extra:'2026'}</small></div>${photoGrid(g)}${design===5?`<div class="photo-caption">${g.ids.length} фото · Открыть любой снимок</div>`:''}</section>`}).join('')}
function catalog(){return `<div class="albumwrap"><div class="summaryline">Альбомы Nextcloud и папки телефона</div><div class="chips">${[['all','Все'],['cloud','Nextcloud'],['local','Телефон']].map(([id,t])=>`<button class="chip ${filter===id?'active':''}" data-dfilter="${id}">${t}</button>`).join('')}</div><div class="grid">${items().filter(a=>filter==='all'||a.source===filter).map(card).join('')}</div>${condition==='empty'?'<div class="empty">Нет доступных альбомов и папок</div>':''}</div>`}
function sheet(){if(!menu)return '';return `<div class="sheetback" data-action="dismiss"><div class="sheet" role="dialog" aria-label="${menu==='account'?'Аккаунт':'Разделы'}"><div class="handle"></div>${menu==='account'?`<h3>Nextcloud</h3><p>Демоаккаунт · Подключён<br>Версия Memories, серверный путь и служебные счётчики живут здесь, а не над фотографиями.</p><button data-action="refresh">↻ Обновить медиатеку</button><p>Выход из аккаунта — в настройках подключения.</p>`:`<h3>Библиотека</h3><button data-dpage="timeline">▦ Фото <span class="sub">· по времени</span></button><button data-dpage="albums">▣ Альбомы <span class="sub">· подборки и папки</span></button>`}<button data-action="dismiss" class="accountlink">Закрыть</button></div></div>`}
function customRender(){
if(selected&&selected.id!=='timeline'){baseRender();}else{let banner=condition==='offline'?'<div class="notice statebanner">Нет связи с Nextcloud · показаны сохранённые фото</div>':condition==='partial'?'<div class="notice statebanner">Доступ к части фото телефона. Медиатека может быть неполной.</div>':'';$('screen').innerHTML=top()+banner+(page==='timeline'?timelineHtml():catalog());renderViewer()}
const c=study[design];document.body.className=`design design${design}`;$('headline').textContent=c[1];$('description').textContent=c[2];$('verdict').textContent=c[3];$('tradeoff').textContent=c[4];$('scenario').textContent='Перейди из фото в альбомы и обратно. Открой снимок, пролистай и вернись. Меню ⋯ показывает, куда убрать служебную информацию.';$('variantlabel').textContent=`${design} / 5 · ${c[0]}`;
let chrome=$('designChrome');if(!chrome){chrome=document.createElement('div');chrome.id='designChrome';document.querySelector('.phone').append(chrome)}
let nav='';if(!selected){if(design===2)nav=`<nav class="floatingnav">${navButton('timeline','▦ Фото')}${navButton('albums','▣ Альбомы')}</nav>`;if(design===3)nav=`<nav class="sideways">${navButton('timeline','Фото')}${navButton('albums','Альбомы')}</nav>`;if(design===5)nav=`<button class="pullnav" data-action="sections">Библиотека <span>${section()} ⌃</span></button>`}chrome.innerHTML=nav+sheet();
$('state').textContent=JSON.stringify({design,page,albumModel:'A: общий каталог без объединения по имени',condition,container:selected?.id||null,photoIndex:photo,month,menu},null,2);
let link=$('oldStudies');if(!link){link=document.createElement('a');link.id='oldStudies';link.className='study-link';link.href='?variant=A';link.textContent='← Предыдущие варианты альбомов A / B / C';document.querySelector('aside').append(link)}
}
render=customRender;
changeVariant=delta=>{design=(design-1+delta+5)%5+1;let u=new URL(location);u.searchParams.delete('variant');u.searchParams.set('design',design);history.replaceState(null,'',u);page='timeline';selected=null;photo=null;menu=null;render();$('screen').scrollTop=0};
document.addEventListener('click',e=>{
const b=e.target.closest('button,[data-action]');if(!b)return;
if(b.dataset.dpage){e.stopImmediatePropagation();page=b.dataset.dpage;selected=null;photo=null;menu=null;render();$('screen').scrollTop=navScroll;return}
if(b.dataset.action){e.stopImmediatePropagation();if(b.dataset.action==='dismiss'&&e.target.closest('.sheet')&&!e.target.closest('button'))return;menu=b.dataset.action==='dismiss'?null:b.dataset.action;const refresh=menu==='refresh';if(refresh)menu=null;render();if(refresh){const t=document.createElement('div');t.className='toast';t.textContent='Демо: медиатека обновлена только в памяти прототипа';document.querySelector('.phone').append(t);setTimeout(()=>t.remove(),2300)}return}
if(b.dataset.dfilter){e.stopImmediatePropagation();filter=b.dataset.dfilter;render();return}
if(b.dataset.day){e.stopImmediatePropagation();$('day'+b.dataset.day).scrollIntoView({behavior:'smooth',block:'start'});return}
if(b.dataset.month){e.stopImmediatePropagation();month=b.dataset.month;render();return}
if(b.dataset.dphoto!==undefined){e.stopImmediatePropagation();navScroll=$('screen').scrollTop;selected={id:'timeline',name:'Все фото',photos:allPhotos};photo=+b.dataset.dphoto;render();return}
if(b.dataset.open){e.stopImmediatePropagation();navScroll=$('screen').scrollTop;selected=albums.find(a=>a.id===b.dataset.open);render();$('screen').scrollTop=0;return}
if(b.dataset.photo!==undefined){detailScroll=$('screen').scrollTop;return}
if(b.id==='closephoto'){e.stopImmediatePropagation();photo=null;const wasTimeline=selected.id==='timeline';if(wasTimeline)selected=null;render();$('screen').scrollTop=wasTimeline?navScroll:detailScroll;return}
if(b.id==='back'){e.stopImmediatePropagation();selected=null;page='albums';render();$('screen').scrollTop=navScroll;return}
},true);
document.addEventListener('keydown',e=>{if(e.key==='Escape'&&menu){menu=null;render()}});render();
})();
