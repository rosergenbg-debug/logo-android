# LAN Мост · Android v2.0.0

Simple two-pane SMB file transfer application for Android. Russian UI, no analytics or ads.

## Как пользоваться

1. Соедините телефон и Windows с одной Wi-Fi сетью. Windows должна предоставлять общую папку по SMB2/SMB3.
2. Откройте «⚙ Подключение к Windows», проверьте IP, общую папку, имя пользователя, пароль. Нажмите «Подключиться и свернуть настройки».
3. В блоке «Мой телефон» нажмите «📂 Выбрать» и выберите папку через системный диалог Android. Доступ сохраняется после перезапуска. Android 11+ не позволяет выбирать некоторые корневые папки, например корень «Загрузки»: выберите разрешённую подпапку.
4. Откройте нужные папки на ПК и телефоне (список каждого устройства прокручивается отдельно). Нажмите на **файлы**, чтобы выделить их галочками. Папки открываются нажатием. Передача папок целиком в v2 пока не предусмотрена.
5. Нажмите **ПК → ТЕЛЕФОН** либо **ТЕЛЕФОН → ПК**. Подтвердите показанные пути источника и назначения.

Последние выбранные папки и профиль компьютера запоминаются; SMB подключается автоматически при сохранённом пароле. Пароль шифруется через Android Keystore. Передача осуществляется по локальной SMB-сети, без облачного посредника. Приложение не удаляет оригиналы и не перезаписывает существующие файлы молча.

**Путь Windows:** отображается сетевой путь `\\server\share\folder`. Диск `D:` можно просматривать, только если соответствующий раздел/папка опубликованы как общий ресурс Windows. Приложение не имеет права показывать все диски Windows без настройки общего доступа.

## Подпись и обновления

`de.roserge.lanmost.v2` — новый идентификатор пакета. Версия 2 ставится **рядом с v1**, поэтому удалять рабочую первую версию не нужно. CI APK пока подписан тестовым ключом, а не постоянным релизным сертификатом. Следующий CI APK, подписанный другим ключом, не установится поверх него. Для постоянных обновлений требуется создать собственный .jks и сохранить его только в защищённом секретном хранилище GitHub Actions (без публикации в Git); см. `app/build.gradle`. Никогда не публикуйте приватный ключ в репозитории.

## Сборка

Android SDK 35, Java 17, Gradle 8.11.1; `gradle :app:assembleDebug :app:testDebugUnitTest`. GitHub Actions builds and publishes `LAN-Most-v2.0.0.apk` in the isolated `lan-most-v2` branch of the repository. GitHub action uses `android-actions/setup-android@v4`.

## Конфиденциальность

Only Internet permission for SMB connections; file operations on Android use SAF folder permissions chosen explicitly by the user. No `MANAGE_EXTERNAL_STORAGE` request. The IP, user, SMB share, last paths and the encrypted password are stored locally on-device. No passwords or Wi-Fi configuration appear in the repository.
