# Lap Race

Клиентский мод для Minecraft (Fabric): гонки на лодках с кругами, позициями, секторами и HUD в стиле автосимуляторов.

*English below.*

## Что умеет

- **Круги и время:** счёт кругов, время круга и секторов, личные рекорды, таблица позиций с отставанием от лидера.
- **HUD как в автосимах:** спидометр, педали, центральный дисплей со страницами, всплывающие сектора и лучший круг. Может заменить панель мода Boat HUD.
- **Гонки:** локальные гонки на N кругов, старт отсчётом или стартовыми огнями, финиш, результаты.
- **Трассы route1–route6** уже в моде, свои можно записать.
- **Пит-стопы:** пит-лейн с ограничением скорости, топливо, выбор бокса, автоматический заезд в бокс.
- **Болид:** лодка выглядит как болид F1 (или машина безопасности), есть ливреи, гараж и своя ливрея из PNG.
- **Подсказки:** гоночная линия и «призрак» лучшего круга.
- **Звук:** двигатель V8 с турбиной.
- **Онлайн:** круги и позиция отправляются на сервер Lap Race (сайт с таблицами и живой картой), гонки и турниры проводит сервер.

## Установка

1. Поставь **Minecraft 26.3** с загрузчиком **[Fabric](https://fabricmc.net/use/)** (0.19.5 или новее) и **Java 25**.
2. Положи в папку `mods`:
   - **[Fabric API](https://modrinth.com/mod/fabric-api)** (обязательно);
   - сам мод `laprace-<версия>.jar`.
3. Запусти игру.

По желанию, если хочешь:
- [Mod Menu](https://modrinth.com/mod/modmenu): окно настроек мода из меню модов;
- [Boat HUD](https://modrinth.com/mod/boathud): Lap Race может заменить его панель своей;
- [AlinLib](https://modrinth.com/mod/alinlib): уведомления в виде всплывающих карточек;
- [Simple Voice Chat](https://modrinth.com/mod/simple-voice-chat): радио с пит-стеной.

Мод клиентский: на сервер Minecraft его ставить не нужно.

## Онлайн и прокси (важно для игроков из России)

Онлайн-функции ходят на сервер Lap Race. Из России соединение с ним часто обрывается провайдером.
Если онлайн не работает, открой настройки мода → **Прокси**:

- **Системный:** если VPN-приложение включено в режиме «системный прокси»;
- **Свой адрес:** впиши `хост:порт` HTTP-прокси, например `127.0.0.1:10809` (локальный порт VPN-приложения). SOCKS не подойдёт.

Сама игра (сервер Minecraft) от этой настройки не зависит.

## Клавиши (по умолчанию, меняются в настройках управления)

`R`: гонка · `]`: следующая трасса · `I`: таблица · `G`: гараж. Остальное в разделе «Lap Race» настроек управления.

## Сборка из исходников

```
./gradlew build
```

Готовый мод: `build/libs/laprace-<версия>.jar` (файл `-sources.jar` для игры не нужен).

## Авторы и лицензии

- Код мода: лицензия [MIT](LICENSE).
- Модели болида F1 и машины безопасности: **CoBeHok**.
- Звук двигателя: движок «tsp» из [Stunt Rally 3](https://github.com/stuntrally/stuntrally3), записал CryHam, лицензия [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/); звуки доработаны для Lap Race. Турбина, сброс давления и стартер синтезированы для Lap Race.
- Шрифты [Inter](https://github.com/rsms/inter) и [Michroma](https://github.com/googlefonts/Michroma-font): лицензия SIL Open Font License 1.1 (тексты в `assets/laprace/licenses`).

---

## English

**Lap Race** is a client-side Fabric mod for Minecraft 26.3: boat races with lap and sector timing, a position board, a racing-sim style HUD, pit stops with fuel, an F1 car model with liveries, a racing line, a best-lap ghost, a V8 engine sound and online races through the Lap Race server.

**Requires:** Minecraft 26.3, Fabric Loader 0.19.5+, Fabric API, Java 25.
**Optional:** Mod Menu, Boat HUD, AlinLib, Simple Voice Chat.

If the online features cannot reach the server (common in Russia), set **Proxy** in the mod's settings to *System* or to your own HTTP proxy address.

Code: MIT. Car models: CoBeHok. Engine sound: Stunt Rally 3 by CryHam (CC-BY-4.0). Fonts: Inter and Michroma (OFL 1.1).
