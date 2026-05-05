# Участь у розробці / Contributing

Цей документ пояснює внутрішню будову проєкту — як він влаштований, чому
прийняті ті чи інші рішення і як додавати нові можливості. Написано
українською, для тих хто тільки знайомиться з кодом.

---

## Зміст

1. [Що робить програма](#що-робить-програма)
2. [Загальна архітектура](#загальна-архітектура)
3. [Фази виконання](#фази-виконання)
4. [Опис класів](#опис-класів)
5. [Ключові патерни та рішення](#ключові-патерни-та-рішення)
6. [Потокова безпека](#потокова-безпека)
7. [Протокол NETCONF](#протокол-netconf)
8. [Як побудувати та запустити локально](#як-побудувати-та-запустити-локально)
9. [Як додати підтримку нового вендора](#як-додати-підтримку-нового-вендора)
10. [Стиль коду](#стиль-коду)

---

## Що робить програма

Мережевий провайдер має десятки маршрутизаторів різних виробників (Juniper,
Cisco, MikroTik). На кожному з них налаштовані сотні VRF, VPLS-контурів,
L2-circuit'ів тощо. Відслідкувати «хто де і в якому стані» вручну — складно.

Програма раз на добу:
1. Підключається до кожного маршрутизатора і зчитує конфігурацію.
2. Об'єднує всі дані в одну структуру — один сервіс, що присутній на кількох
   роутерах, відображається одним рядком.
3. Знаходить «підозрілі» ситуації: L2CIRCUIT/VPLS без парного запису на
   сусідньому кінці, з'єднання у стані down.
4. Генерує HTML-звіт і кладе його у директорію nginx — будь-хто з команди
   відкриває браузер і одразу бачить повну картину.

---

## Загальна архітектура

```
RoutingInstancesReport.main()
        │
        ├─ Фаза 1 ──▶ JuniperCollector × N хостів (паралельно)
        │               └─ NETCONF/SSH → XML → xmlCache + disk dump
        │
        ├─ Фаза 2 ──▶ JuniperSwitchCollector     ┐
        │             JuniperL2circuitCollector    ├─ читають xmlCache (паралельно)
        │             JuniperBridgedomainsCollector┘
        │             CiscoCollector × M хостів   ─ Telnet (паралельно)
        │             RouterOSCollector × K хостів ─ SSH (паралельно)
        │
        ├─ LoAddressMapper.build()   ← читає xmlCache → IP→ім'я словник
        ├─ findOrphans()             ← аналіз пар L2CIRCUIT/VPLS
        │
        ├─ Фаза 3 ──▶ JuniperDownStateCollector × N хостів (паралельно)
        │               └─ NETCONF operational RPC (get-l2ckt + get-vpls з <down/>)
        │
        └─ ReportGenerator.generate() → HTML файл → nginx
```

Всі зібрані дані зберігаються у двох спільних структурах:
- `instances: TreeMap<String, RoutingInstance>` — основна таблиця, відсортована
  за ключем дедублікації.
- `vrfVplsList: LinkedHashMap<String, Map<String, String>>` — індекс RD
  (Route Distinguisher) у порядку першого зустрічання.

---

## Фази виконання

### Чому три фази, а не одна?

**Фаза 1** (тільки `JuniperCollector`) має завершитися перша, бо вона пише
XML-конфігурацію кожного роутера в пам'ять (`xmlCache`) і на диск. Усі інші
Juniper-колектори читають ці дані — якщо запустити їх одночасно з першою
фазою, вони спробують читати ще не записаний кеш.

**Фаза 2** — три дискові Juniper-колектори (Switch/L2circuit/Bridgedomains)
плюс Cisco і RouterOS — між собою незалежні, тому виконуються паралельно.
Дискові колектори не роблять мережевих запитів (читають `xmlCache`) — вони
швидкі і не займають «слоти» семафора.

**Фаза 3** (`JuniperDownStateCollector`) — після `LoAddressMapper.build()`,
бо потребує словник IP→ім'я для перетворення IP-адрес сусідів у читабельні
імена маршрутизаторів у звіті.

### Обмеження паралельності — Semaphore

```java
Semaphore semaphore = new Semaphore(MAX_CONCURRENT_QUERIES); // = 5
```

Семафор обмежує кількість одночасних мережевих з'єднань. Без нього 10+
паралельних SSH-сесій могли б перевантажити роутери або вичерпати ресурси
хоста. Семафор захоплюється перед підключенням і звільняється після (у
`finally`, щоб звільнити навіть при помилці).

### Virtual Threads (Java 21)

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    // submit tasks...
} // <- тут executor.close() чекає завершення всіх задач
```

Virtual threads — це легкі потоки Java 21, які не блокують OS-потік під час
очікування I/O (SSH/Telnet). Замість 30 секунд на 10 роутерів послідовно —
отримуємо ~30 секунд на всі одночасно (обмежено семафором до 5 паралельних).
`try-with-resources` гарантує, що `main()` рухається далі тільки після того
як всі задачі фази завершені.

---

## Опис класів

### `Collector` (інтерфейс)

Єдиний метод:
```java
void collect(String hostname,
             Map<String, RoutingInstance> instances,
             Map<String, Map<String, String>> vrfVplsList) throws Exception;
```

Кожна реалізація: підключається до роутера, парсить відповідь, викликає
`RoutingInstance.merge()` для кожного знайденого сервісу. Помилки кидаються
наверх — `main()` їх ловить, логує і продовжує з наступним хостом.

---

### `AbstractJuniperCollector`

Базовий клас для всіх Juniper-колекторів. Містить:

**NETCONF-транспорт:**
- `fetchRpcs(hostname, rpcs)` — відкриває SSH-сесію, обмінюється NETCONF
  hello-повідомленнями, послідовно відсилає кожен RPC і зчитує відповідь,
  закриває сесію. Повертає список відповідей. Ключова деталь: один виклик
  `fetchRpcs` = одна SSH-сесія, скільки б RPC не було у списку.
- `fetchNetconf(hostname)` — зручна обгортка для одного RPC
  `get-config` (отримати running-конфігурацію).
- `readOrFetch(hostname)` — перевіряє `xmlCache` → диск → мережа. Завдяки
  цьому методу три дискові колектори фази 2 не роблять жодного нового
  підключення.

**XML-хелпери:**
- `parseXml(xml)` — парсить рядок у DOM-документ.
- `extractRouterName(doc, xp, fallback)` — витягує ім'я роутера з
  `//system/host-name`, відрізає суфікс `-re0`/`-re1` (подвійні RE на
  Juniper), повертає у верхньому регістрі.

**Спільні константи:**
- `DELIM = "]]>]]>"` — роздільник NETCONF 1.0 (RFC 6241).
- `DUMP_DIR` — читається з env `DUMP_DIR`, за замовчуванням `/tmp`.
- `xmlCache` — `ConcurrentHashMap<String, String>` переданий ззовні.

**Важлива деталь про `leftover`:** під час читання NETCONF-відповіді з потоку
може прийти більше байт ніж один RPC (наступний RPC вже «прийшов» у буфер).
Ці «зайві» байти зберігаються у `leftover` і використовуються на початку
наступного читання. `leftover` — **локальна змінна** `fetchRpcs()`, а не поле
класу, тому кілька паралельних викликів на одному екземплярі колектора не
заважають одне одному.

---

### `JuniperCollector`

Фаза 1. Отримує повну running-конфігурацію через `get-config`, зберігає у
`xmlCache` і атомарно пише на диск, потім парсить
`//routing-instances/instance`.

**Типи інстансів:**

| XML `instance-type` | Тип у звіті | Умова |
|---------------------|-------------|-------|
| `vrf`               | `VRF`       | завжди |
| `vpls`              | `VPLS/L2`   | немає `<routing-interface>` |
| `vpls`              | `VPLS/L3`   | є `<routing-interface>` (IRB) |

Для VPLS з LDP-сусідами і vpls-id додається **вторинний запис** з ключем
`vpls-id/ROUTER (instance-name)`. Це потрібно щоб у відсортованій таблиці
пов'язані контури стояли поруч (наприклад, `334/R418-1` і `334/R201-1`
будуть поруч незалежно від алфавітного порядку імен інстансів).

**Атомарний запис на диск:**
```java
Path tmp = Files.createTempFile(Path.of(DUMP_DIR), "juniper-" + hostname + "-", ".xml");
Files.writeString(tmp, xml);
Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
```
Читач (наступний запуск або інший процес) ніколи не побачить файл
наполовину записаним: він або отримає старий повний файл, або новий повний —
завдяки атомарному rename (syscall `rename(2)` на Linux).

---

### `JuniperSwitchCollector`, `JuniperL2circuitCollector`, `JuniperBridgedomainsCollector`

Фаза 2 (дискові). Всі три викликають `readOrFetch()` — з `xmlCache` без
жодного мережевого з'єднання. Парсять різні XPath у тому самому XML-документі.

| Клас | XPath | Тип |
|------|-------|-----|
| `JuniperSwitchCollector` | `//protocols/connections/interface-switch` | `SWITCH` |
| `JuniperL2circuitCollector` | `//protocols/l2circuit/neighbor/interface` | `L2CIRCUIT` |
| `JuniperBridgedomainsCollector` | `//bridge-domains/domain` | `BRIDGE/L2` або `BRIDGE/L3` |

Усі ігнорують вузли всередині `<dynamic-profiles>` (шаблони, не реальні
інстанси).

---

### `JuniperDownStateCollector`

Фаза 3. Не реалізує `collect()` (кидає `UnsupportedOperationException`) —
замість цього має спеціалізований метод `collectDownState(hostname, loAddresses)`.

Відправляє **два NETCONF operational RPC в одній SSH-сесії:**
```
get-l2ckt-connection-information<down/>   → відповідь 1
get-vpls-connection-information<down/>    → відповідь 2
```

Саме тут `fetchRpcs(hostname, List.of(L2CKT_DOWN_RPC, VPLS_DOWN_RPC))`
показує свою перевагу: два RPC — одна сесія, а не два окремих підключення.

Результат — список рядків `String[]` по 6 елементів:
`[тип, роутер, vc-id, instance, сусід, статус]`

**`resolveRouterName()`** — потрібне ім'я роутера у верхньому регістрі таке
саме, як у головній таблиці. Шукає у `xmlCache` (є після фази 1), якщо немає
— на диску, якщо немає — `hostname.toUpperCase()`.

---

### `ConnectionStatus` (enum)

Перетворює коди статусів з XML (`NP`, `OL`, `LD`, `VC-Dn`…) у читабельний
текст. Метод `describe(code)` нормалізує код (заміна `-` на `_`, верхній
регістр) і шукає серед значень enum. Два спеціальні випадки: `"->"` і `"<-"`
(тільки один напрям з'єднання активний) обробляються окремо бо не є
валідними іменами Java-констант.

---

### `CiscoCollector`

Підключається через **Telnet** (Apache Commons Net), входить у режим enable,
виконує `show running-config`, парсить блоки `ip vrf ... rd ...` регулярними
виразами. Cisco не підтримує NETCONF у старих версіях IOS.

---

### `RouterOSCollector`

Підключається через **SSH**, виконує `/ip route vrf export compact`, парсить
вивід. Рядки-продовження (backslash + newline у MikroTik) склеюються перед
парсингом.

---

### `RoutingInstance` + `HashUtils`

**`RoutingInstance`** — модель даних одного сервісу. Lombok `@Data` генерує
конструктор, геттери/сеттери, `equals`, `hashCode`. Поле `hosts: List<String>`
містить по одному рядку на кожен роутер де присутній цей сервіс.

**`merge()` — центральний метод всього збору:**
```java
static synchronized void merge(instances, vrfVplsList, name, type, rd, hostEntry)
```

1. Будує ключ через `HashUtils.computeKey()`.
2. `instances.computeIfAbsent(key, ...)` — або знаходить існуючий запис, або
   створює новий.
3. Додає `hostEntry` до списку `hosts`.
4. Якщо є RD — реєструє в `vrfVplsList` для RD-індексу.

`synchronized` потрібен бо `merge()` викликається з паралельних потоків і
виконує складену операцію (кілька кроків як одна транзакція) над незахищеними
`TreeMap`/`LinkedHashMap`.

**`HashUtils.computeKey()`** — ключ дедублікації, сумісний з оригінальною
Perl-реалізацією:
```
"ім'я, доповнене до 50 символів" + ":" + MD5(ім'я+тип) + ":" + SHA1(ім'я+тип)
```
Завдяки цьому один VRF на 10 роутерах дає один рядок у звіті з переліком
всіх 10 роутерів.

---

### `LoAddressMapper`

Читає `xmlCache` (або диск) для кожного Juniper-хосту і будує словник
`lo0-адреса → ім'я роутера`. XPath:
```
//interfaces/interface[name='lo0']/unit/family/*/address/name
```
Зірочка `*` охоплює будь-яке сімейство адрес (inet, inet6) без їх явного
перерахування. Адреси зберігаються без префікс-довжини (`/32`, `/128`).

Цей словник використовується у двох місцях:
- `ReportGenerator` — замінює IP-адреси сусідів у головній таблиці на
  `ROUTERNAME/IP`.
- `JuniperDownStateCollector` — те саме для down-state таблиці.

---

### `RoutingInstancesReport` (точка входу)

`main()` читає env vars, створює спільні структури (`instances`, `vrfVplsList`,
`xmlCache`, `semaphore`), запускає три фази, будує lo0-карту, шукає orphans,
збирає down-state, генерує звіт.

Два приватних хелпери:
- `runParallel(hosts, task)` — подає задачі у virtual-thread executor і чекає
  всіх через `try-with-resources`.
- `findOrphans(instances, loAddresses)` — для кожного L2CIRCUIT і VPLS з
  ключем `число/ROUTER` перевіряє чи є парний запис на сусідньому кінці.
  Використовує `vcidRouterSet: Map<vcId, Set<routerName>>` — набір усіх
  роутерів, що мають запис для даного VC-ID (незалежно від типу L2CIRCUIT чи
  VPLS, бо вони можуть бути парою одне одному).

---

### `ReportGenerator`

Статичний метод `generate()` приймає всі зібрані дані і будує HTML рядковою
заміною у шаблоні (`HTML_TEMPLATE`). П'ять розділів:

1. **VRF/VPLS за RD** — `buildVrfList()`: сортування за числовим добутком
   AS×ID з RD-рядка.
2. **VC-ID/VPLS-ID** — `buildVcidList()`: групування записів `число/ROUTER`
   за числовим префіксом.
3. **Основна таблиця** — `buildVrfInfo()`: IP-адреси в колонці Маршрутизатор
   замінюються на `ROUTERNAME/IP` регулярним виразом по словнику lo0.
4. **L2CIRCUIT/VPLS без пар** — `buildOrphanTable()`.
5. **L2CIRCUIT/VPLS неактивний стан** — `buildDownStateTable()`: сортування
   за типом → роутер → числовий VC-ID → instance.

---

## Ключові патерни та рішення

### Чому XPath, а не JSON/RESTCONF?

Juniper JunOS повертає конфігурацію у XML. NETCONF — стандартний протокол
управління мережевим обладнанням (RFC 6241), підтримується всіма сучасними
Juniper-пристроями. XPath дозволяє точно описати що саме шукати у XML без
ручного парсингу.

### Чому Telnet для Cisco, а не SSH?

Старі версії Cisco IOS не підтримують NETCONF, а SSH на них може бути
вимкнений або не налаштований. Telnet — найменший спільний знаменник.

### Чому `LinkedHashMap` для `vrfVplsList`, а не `ConcurrentHashMap`?

`LinkedHashMap` зберігає порядок вставки. У RD-індексі важливо щоб записи
йшли у тому порядку, в якому вперше зустрічалися при зборі — так
пов'язані контури стоять поруч. `ConcurrentHashMap` порядку не гарантує.
Потокова безпека забезпечується через `synchronized merge()`, а не через
concurrent-колекцію.

### Чому `ConcurrentHashMap` для `xmlCache`?

Тут порядок не важливий — кожен хост пише свій унікальний ключ. Читачі фази 2
звертаються одночасно до різних ключів. `ConcurrentHashMap` тут ідеальний: 
`put` і `get` на різних ключах не конфліктують і не потребують зовнішньої
синхронізації.

---

## Потокова безпека

| Структура | Тип | Захист |
|-----------|-----|--------|
| `instances` | `TreeMap` | `synchronized merge()` |
| `vrfVplsList` | `LinkedHashMap` | `synchronized merge()` |
| `xmlCache` | `ConcurrentHashMap` | вбудований (lock-striped) |
| `downConnections` | `synchronizedList(ArrayList)` | `Collections.synchronizedList` |
| Поля колекторів | `final String` | незмінні, захисту не потребують |

`AbstractJuniperCollector` не має змінюваного стану (поле `leftover` є
локальною змінною у `fetchRpcs()`) — тому один екземпляр колектора можна
безпечно використовувати з кількох потоків одночасно.

---

## Протокол NETCONF

NETCONF (RFC 6241) — це XML-протокол для управління мережевим обладнанням
поверх SSH. Спрощена діаграма сесії:

```
Клієнт                                    Роутер
  │                                          │
  │──── SSH connect ────────────────────────▶│
  │◀─── <hello> (можливості роутера) ────────│  ← readUntilDelimiter (server hello)
  │──── <hello> (наші можливості) + ]]>]]> ─▶│  ← send(NETCONF_HELLO)
  │                                          │
  │──── <rpc> get-config </rpc> + ]]>]]> ───▶│  ← send(rpc)
  │◀─── <rpc-reply> ... </rpc-reply> ]]>]]>  │  ← readUntilDelimiter (відповідь)
  │                                          │
  │──── <rpc> close-session </rpc> + ]]>]]> ▶│
  │                                          │
  │──── SSH disconnect ─────────────────────▶│
```

`]]>]]>` — роздільник NETCONF 1.0 (DELIM). Він сигналізує кінець кожного
повідомлення, бо XML може мати будь-яку довжину і клієнт інакше не знав би
коли повідомлення закінчилося.

`readUntilDelimiter()` читає байти з потоку поки не зустріне `]]>]]>`. Байти
що прийшли після роздільника (наступне повідомлення вже «в дорозі») зберігаються
у `leftover` і використовуються на початку наступного виклику — щоб не втратити
жодного байта.

---

## Як побудувати та запустити локально

```bash
# Зібрати fat JAR (містить всі залежності)
mvn package -DskipTests

# Запустити проти одного роутера (підставте реальні значення)
ROUTER_USER=admin \
ROUTER_PASS=secret \
JUNIPER_HOSTS=my-router-1 \
REPORT_PATH=/tmp/report.html \
LOG_LEVEL=debug \
java -jar target/routing-instances-report-1.0.jar

# Зібрати Docker-образ
docker build -t routing-instances-report .

# Перегенерувати діаграму класів і Javadoc
mvn -P docs generate-resources
# → docs/classes.svg
# → target/reports/apidocs/
```

---

## Як додати підтримку нового вендора

Припустимо, треба додати підтримку **Huawei VRP**.

### 1. Створити клас колектора

```java
@Log4j2
public class HuaweiCollector implements Collector {

    private final String login;
    private final String pass;

    public HuaweiCollector(String login, String pass) {
        this.login = login;
        this.pass = pass;
    }

    @Override
    public void collect(String hostname,
                        Map<String, RoutingInstance> instances,
                        Map<String, Map<String, String>> vrfVplsList) throws Exception {
        // підключитися до роутера (SSH, Telnet, NETCONF — як підтримує пристрій)
        // розібрати відповідь
        // для кожного знайденого сервісу:
        RoutingInstance.merge(instances, vrfVplsList, name, type, rd, hostEntry);
    }
}
```

### 2. Додати env var

У `RoutingInstancesReport.main()`:
```java
List<String> huaweiHosts = parseList(env("HUAWEI_HOSTS", ""));
```

### 3. Додати у фазу 2

```java
Collector huawei = new HuaweiCollector(login, pass);
runParallel(huaweiHosts, host -> {
    semaphore.acquireUninterruptibly();
    try { huawei.collect(host, instances, vrfVplsList); }
    finally { semaphore.release(); }
});
```

### 4. Задокументувати env var у README і CONTRIBUTING.md

---

## Стиль коду

- **Java 21**, компіляція через `maven.compiler.release=21`.
- **Lombok** використовується лише для `@Data` на `RoutingInstance` і
  `@Log4j2` на всіх класах. Не додавати `@Builder`, `@SneakyThrows` та інше
  без потреби.
- **Коментарі** — тільки Javadoc на публічних/package-private методах. Не
  коментувати очевидний код.
- **Логування** — `log.info` для подій що важливі оператору, `log.debug` для
  деталей що потрібні при діагностиці, `log.warn` для проблем що не зупиняють
  виконання.
- **Обробка помилок** — колектори кидають `Exception` наверх; `main()` їх
  ловить, логує і продовжує з наступним хостом. Не ковтати помилки мовчки.
- **Тести** — наразі відсутні (основний інтеграційний тест — Docker-збірка і
  реальні роутери). Якщо додаєте юніт-тести — `src/test/java/...`, той самий
  пакет.
