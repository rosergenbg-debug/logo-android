package de.roserge.lanmost;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** LAN Most v2: two visible browsers, explicit copy directions, saved destinations. */
public final class MainActivity extends Activity {
    private static final int PICK_PHONE_FOLDER = 2102;
    private static final int BG = Color.rgb(242, 246, 251);
    private static final int INK = Color.rgb(34, 43, 70);
    private static final int MUTED = Color.rgb(99, 112, 136);
    private static final int INDIGO = Color.rgb(103, 65, 180);
    private static final int TEAL = Color.rgb(0, 141, 128);
    private static final int BLUE = Color.rgb(46, 105, 221);
    private static final int LIGHT_BLUE = Color.rgb(232, 240, 255);
    private static final int LIGHT_TEAL = Color.rgb(229, 249, 243);
    private static final int RED = Color.rgb(182, 49, 62);

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<String> pcPath = new ArrayList<>();
    private final ArrayList<String> phonePath = new ArrayList<>();
    private final ArrayList<SmbClient.Entry> pcEntries = new ArrayList<>();
    private final ArrayList<DocumentFile> phoneEntries = new ArrayList<>();
    private final Set<String> selectedPc = new HashSet<>();
    private final Set<String> selectedPhone = new HashSet<>();

    private SharedPreferences prefs;
    private SavedConnections profiles;
    private SmbClient smb;
    private String hostConnected = "", shareConnected = "";
    private Uri phoneTreeUri;
    private DocumentFile currentPhoneDirectory;
    private boolean busy = false;
    private boolean connectionExpanded = false;
    private EditText host, share, user, domain, password;
    private CheckBox savePassword;
    private LinearLayout connectionFields;
    private TextView connectionToggle, connectionSummary, pcPathView, phonePathView, pcCount, phoneCount, status;
    private ListView pcList, phoneList;
    private BaseAdapter pcAdapter, phoneAdapter;
    private Button connectButton, pcUpButton, phoneUpButton, phoneChooseButton, toPhoneButton, toPcButton;
    private ProgressBar progress;

    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        profiles = new SavedConnections(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        buildLayout();
        restorePhonePlace();
        renderPhonePath();
        if (PasswordStore.hasSavedPassword(this) && !PasswordStore.load(this).isEmpty()) {
            setConnectionExpanded(false);
            connect();
        } else {
            setConnectionExpanded(true);
            status.setText("Выбери папку телефона и подключи Windows");
            refreshPhone();
        }
    }

    private void buildLayout() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(BG);
        setContentView(screen);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(10), dp(18), dp(8));
        screen.addView(header);
        TextView title = label("LAN Мост", 25, INK, true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView version = label("v2.0.0", 14, INDIGO, true);
        version.setPadding(dp(12), dp(5), dp(12), dp(5));
        version.setBackground(shape(LIGHT_BLUE, 11, 0, 0));
        header.addView(version);

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(false);
        scroller.setClipToPadding(false);
        screen.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = vertical();
        content.setPadding(dp(13), dp(2), dp(13), dp(12));
        scroller.addView(content);
        buildConnectionCard(content);
        buildPcCard(content);
        buildPhoneCard(content);

        LinearLayout footer = vertical();
        footer.setBackground(shape(Color.WHITE, 19, 0, 0));
        footer.setPadding(dp(14), dp(9), dp(14), dp(13));
        footer.setElevation(dp(7));
        screen.addView(footer);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        footer.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        status = label("Готов к передаче", 12, MUTED, false);
        status.setMaxLines(2);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setPadding(dp(5), dp(4), dp(5), dp(8));
        footer.addView(status);
        toPhoneButton = button("↓  С КОМПЬЮТЕРА  →  НА ТЕЛЕФОН", BLUE);
        footer.addView(toPhoneButton, new LinearLayout.LayoutParams(-1, dp(49)));
        toPhoneButton.setOnClickListener(v -> confirmCopy(true));
        toPcButton = button("↑  С ТЕЛЕФОНА  →  НА КОМПЬЮТЕР", TEAL);
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(-1, dp(49));
        second.topMargin = dp(7);
        footer.addView(toPcButton, second);
        toPcButton.setOnClickListener(v -> confirmCopy(false));
        updateActions();
    }

    private void buildConnectionCard(LinearLayout page) {
        LinearLayout frame = card(INDIGO);
        page.addView(frame, cardLp(0));
        LinearLayout summaryRow = horizontal();
        summaryRow.setGravity(Gravity.CENTER_VERTICAL);
        frame.addView(summaryRow);
        LinearLayout summaryTexts = vertical();
        summaryRow.addView(summaryTexts, new LinearLayout.LayoutParams(0, -2, 1));
        summaryTexts.addView(label("⚙  Подключение к Windows", 16, INK, true));
        connectionSummary = label("ПК не подключён  ·  нажми для настройки", 12, MUTED, false);
        connectionSummary.setPadding(0, dp(3), 0, 0);
        summaryTexts.addView(connectionSummary);
        connectionToggle = label("⌄", 25, INDIGO, true);
        connectionToggle.setPadding(dp(12), 0, 0, 0);
        summaryRow.addView(connectionToggle);
        summaryRow.setOnClickListener(v -> {
            setConnectionExpanded(!connectionExpanded);
        });
        connectionFields = vertical();
        LinearLayout.LayoutParams fldLp = new LinearLayout.LayoutParams(-1, -2);
        fldLp.topMargin = dp(10);
        frame.addView(connectionFields, fldLp);
        host = input(connectionFields, "IP или имя компьютера", prefs.getString("host", "192.168.178.21"));
        share = input(connectionFields, "Общий ресурс (например, Download)", prefs.getString("share", "Download"));
        user = input(connectionFields, "Пользователь Windows", prefs.getString("user", "Oneplus"));
        domain = input(connectionFields, "Домен (обычно пустой)", prefs.getString("domain", ""));
        password = input(connectionFields, "Пароль", PasswordStore.load(this));
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        savePassword = new CheckBox(this);
        savePassword.setText("Запоминать пароль защищённо");
        savePassword.setTextSize(13);
        savePassword.setTextColor(INK);
        savePassword.setChecked(PasswordStore.hasSavedPassword(this));
        connectionFields.addView(savePassword);
        connectButton = button("Подключиться и свернуть настройки", INDIGO);
        connectionFields.addView(connectButton, new LinearLayout.LayoutParams(-1, dp(49)));
        connectButton.setOnClickListener(v -> connect());
        LinearLayout options = horizontal();
        LinearLayout.LayoutParams optionsLp = new LinearLayout.LayoutParams(-1, dp(44));
        optionsLp.topMargin = dp(7);
        connectionFields.addView(options, optionsLp);
        Button save = button("＋ Сохранить профиль", TEAL);
        options.addView(save, new LinearLayout.LayoutParams(0, -1, 1));
        save.setTextSize(12);
        save.setOnClickListener(v -> saveProfile());
        Button choose = button("Мои компьютеры", BLUE);
        choose.setTextSize(12);
        LinearLayout.LayoutParams chooseLp = new LinearLayout.LayoutParams(0, -1, 1);
        chooseLp.leftMargin = dp(7);
        options.addView(choose, chooseLp);
        choose.setOnClickListener(v -> chooseProfile());
        TextView credit = label("Разработчик: Serge · roserge.nbg@gmail.com", 11, MUTED, false);
        credit.setGravity(Gravity.CENTER);
        credit.setPadding(0, dp(11), 0, dp(2));
        connectionFields.addView(credit);
        credit.setOnClickListener(v -> {
            Intent email = new Intent(Intent.ACTION_SENDTO,
                Uri.parse("mailto:roserge.nbg@gmail.com?subject=LAN%20Most"));
            try { startActivity(email); } catch (Exception e) { toast("Почтовое приложение не найдено"); }
        });
        setConnectionExpanded(false);
    }

    private void buildPcCard(LinearLayout page) {
        LinearLayout box = card(BLUE);
        page.addView(box, cardLp(10));
        LinearLayout top = horizontal();
        top.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(top);
        top.addView(label("🖥️  МОЙ КОМПЬЮТЕР", 17, BLUE, true), new LinearLayout.LayoutParams(0, -2, 1));
        pcUpButton = button("↑ Выше", BLUE);
        pcUpButton.setTextSize(12);
        top.addView(pcUpButton, new LinearLayout.LayoutParams(dp(84), dp(37)));
        pcUpButton.setOnClickListener(v -> {
            if (busy || pcPath.isEmpty()) return;
            pcPath.remove(pcPath.size() - 1);
            selectedPc.clear();
            openPcFolder();
        });
        pcPathView = label("\\\\192.168.178.21\\Download", 13, INK, true);
        pcPathView.setPadding(dp(2), dp(10), 0, dp(4));
        box.addView(pcPathView);
        pcCount = label("Подключи Windows • нажимай на файл для выбора ✓", 12, MUTED, false);
        box.addView(pcCount);
        pcList = listView();
        box.addView(pcList, listLp());
        pcAdapter = new BaseAdapter() {
            @Override public int getCount() { return pcEntries.size(); }
            @Override public Object getItem(int i) { return pcEntries.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int index, View convert, ViewGroup parent) {
                SmbClient.Entry entry = pcEntries.get(index);
                return fileRow(entry.name, entry.directory, entry.length,
                    selectedPc.contains(entry.name), BLUE, () -> {
                        if (busy) return;
                        if (entry.directory) {
                            pcPath.add(entry.name);
                            selectedPc.clear();
                            openPcFolder();
                        } else {
                            if (!selectedPc.add(entry.name)) selectedPc.remove(entry.name);
                            pcAdapter.notifyDataSetChanged();
                            updateActions();
                        }
                    });
            }
        };
        pcList.setAdapter(pcAdapter);
    }

    private void buildPhoneCard(LinearLayout page) {
        LinearLayout box = card(TEAL);
        page.addView(box, cardLp(10));
        LinearLayout top = horizontal();
        top.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(top);
        top.addView(label("📱  МОЙ ТЕЛЕФОН", 17, TEAL, true), new LinearLayout.LayoutParams(0, -2, 1));
        phoneUpButton = button("↑ Выше", TEAL);
        phoneUpButton.setTextSize(12);
        top.addView(phoneUpButton, new LinearLayout.LayoutParams(dp(84), dp(37)));
        phoneUpButton.setOnClickListener(v -> {
            if (busy || phonePath.isEmpty()) return;
            phonePath.remove(phonePath.size() - 1);
            selectedPhone.clear();
            prefs.edit().putString("phone_path", pathJson(phonePath)).apply();
            refreshPhone();
        });
        phonePathView = label("Папка ещё не выбрана", 13, INK, true);
        phonePathView.setPadding(dp(2), dp(10), 0, dp(4));
        box.addView(phonePathView);
        LinearLayout hintRow = horizontal();
        hintRow.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(hintRow);
        phoneCount = label("Нажми на файл для выбора ✓", 12, MUTED, false);
        hintRow.addView(phoneCount, new LinearLayout.LayoutParams(0, -2, 1));
        phoneChooseButton = button("📂 Выбрать", TEAL);
        phoneChooseButton.setTextSize(12);
        hintRow.addView(phoneChooseButton, new LinearLayout.LayoutParams(dp(112), dp(38)));
        phoneChooseButton.setOnClickListener(v -> choosePhoneFolder());
        phoneList = listView();
        box.addView(phoneList, listLp());
        phoneAdapter = new BaseAdapter() {
            @Override public int getCount() { return phoneEntries.size(); }
            @Override public Object getItem(int i) { return phoneEntries.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int index, View convert, ViewGroup parent) {
                DocumentFile item = phoneEntries.get(index);
                String filename = item.getName() == null ? "Без имени" : item.getName();
                boolean directory = item.isDirectory();
                return fileRow(filename, directory, item.length(),
                    selectedPhone.contains(item.getUri().toString()), TEAL, () -> {
                        if (busy) return;
                        if (directory) {
                            phonePath.add(filename);
                            selectedPhone.clear();
                            prefs.edit().putString("phone_path", pathJson(phonePath)).apply();
                            refreshPhone();
                        } else {
                            String key = item.getUri().toString();
                            if (!selectedPhone.add(key)) selectedPhone.remove(key);
                            phoneAdapter.notifyDataSetChanged();
                            updateActions();
                        }
                    });
            }
        };
        phoneList.setAdapter(phoneAdapter);
    }

    private void setConnectionExpanded(boolean show) {
        connectionExpanded = show;
        if (connectionFields != null) connectionFields.setVisibility(show ? View.VISIBLE : View.GONE);
        if (connectionToggle != null) connectionToggle.setText(show ? "⌃" : "⌄");
    }

    private void connect() {
        if (busy) return;
        String hostValue = host.getText().toString().trim();
        String shareValue = share.getText().toString().trim();
        String userValue = user.getText().toString().trim();
        String domainValue = domain.getText().toString().trim();
        String passValue = password.getText().toString();
        if (passValue.isEmpty()) { setConnectionExpanded(true); toast("Укажи пароль Windows"); return; }
        if (hostValue.isEmpty() || shareValue.isEmpty() || userValue.isEmpty()) {
            setConnectionExpanded(true); toast("Нужны IP, общий ресурс и имя пользователя"); return;
        }
        prefs.edit().putString("host", hostValue).putString("share", shareValue)
            .putString("user", userValue).putString("domain", domainValue).apply();
        try {
            ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(password.getWindowToken(), 0);
        } catch (Exception ignored) { }
        setBusy(true, "Соединяюсь с " + hostValue + "…");
        worker.execute(() -> {
            try {
                SmbClient candidate = new SmbClient(hostValue, shareValue, userValue, domainValue, passValue);
                ArrayList<String> restore = parsePath(prefs.getString(pcKey(hostValue, shareValue), "[]"));
                List<SmbClient.Entry> found;
                try { found = candidate.list(restore); }
                catch (Exception stale) { restore.clear(); found = candidate.list(restore); }
                final ArrayList<String> restored = restore;
                final List<SmbClient.Entry> result = found;
                runOnUiThread(() -> {
                    smb = candidate;
                    hostConnected = hostValue;
                    shareConnected = shareValue;
                    pcPath.clear(); pcPath.addAll(restored);
                    selectedPc.clear();
                    try {
                        if (savePassword.isChecked()) PasswordStore.save(this, passValue);
                        else PasswordStore.clear(this);
                    } catch (Exception e) { toast("Не удалось запомнить пароль: " + explain(e)); }
                    pcEntries.clear(); pcEntries.addAll(result);
                    pcAdapter.notifyDataSetChanged();
                    persistPcPath();
                    renderPcPath();
                    connectionSummary.setText("● " + hostValue + " · " + shareValue + " · подключено");
                    setConnectionExpanded(false);
                    setBusy(false, "Подключено к Windows · " + result.size() + " объектов");
                    if (phoneTreeUri != null) refreshPhone();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    smb = null;
                    pcEntries.clear(); selectedPc.clear();
                    pcAdapter.notifyDataSetChanged();
                    connectionSummary.setText("● Нет подключения · проверь настройки");
                    setConnectionExpanded(true);
                    setBusy(false, "Ошибка подключения: " + explain(e));
                    if (phoneTreeUri != null) refreshPhone();
                });
            }
        });
    }

    private void openPcFolder() {
        if (busy || smb == null) return;
        final ArrayList<String> wanted = new ArrayList<>(pcPath);
        setBusy(true, "Открываю папку компьютера…");
        worker.execute(() -> {
            try {
                List<SmbClient.Entry> result = smb.list(wanted);
                runOnUiThread(() -> {
                    pcEntries.clear(); pcEntries.addAll(result);
                    pcAdapter.notifyDataSetChanged();
                    persistPcPath();
                    renderPcPath();
                    setBusy(false, "Папка ПК: " + result.size() + " объектов");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!pcPath.isEmpty()) pcPath.remove(pcPath.size()-1);
                    renderPcPath();
                    setBusy(false, "Папка недоступна: " + explain(e));
                });
            }
        });
    }

    private void choosePhoneFolder() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, PICK_PHONE_FOLDER); }
        catch (Exception e) { toast("Не удалось открыть папки телефона: " + explain(e)); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_PHONE_FOLDER || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContentResolver().takePersistableUriPermission(uri, flags);
            phoneTreeUri = uri;
            phonePath.clear(); selectedPhone.clear();
            prefs.edit().putString("phone_tree", uri.toString()).putString("phone_path", "[]").apply();
            refreshPhone();
        } catch (Exception e) {
            toast("Нет постоянного доступа к этой папке: " + explain(e));
        }
    }

    private void restorePhonePlace() {
        String previous = prefs.getString("phone_tree", "");
        if (previous.isEmpty()) return;
        try {
            Uri saved = Uri.parse(previous);
            boolean valid = false;
            for (android.content.UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
                if (saved.equals(permission.getUri()) && permission.isReadPermission()
                    && permission.isWritePermission()) { valid = true; break; }
            }
            if (!valid) { prefs.edit().remove("phone_tree").remove("phone_path").apply(); return; }
            phoneTreeUri = saved;
            phonePath.addAll(parsePath(prefs.getString("phone_path", "[]")));
        } catch (Exception e) { phoneTreeUri = null; phonePath.clear(); }
    }

    private void refreshPhone() {
        if (busy) return;
        if (phoneTreeUri == null) {
            currentPhoneDirectory = null;
            phoneEntries.clear();
            if (phoneAdapter != null) phoneAdapter.notifyDataSetChanged();
            renderPhonePath(); updateActions();
            return;
        }
        final Uri rootUri = phoneTreeUri;
        final ArrayList<String> subdirs = new ArrayList<>(phonePath);
        setBusy(true, "Открываю папку телефона…");
        worker.execute(() -> {
            try {
                DocumentFile root = DocumentFile.fromTreeUri(this, rootUri);
                if (root == null || !root.isDirectory()) throw new IllegalStateException("Не найдена корневая папка");
                DocumentFile dir = root;
                boolean reset = false;
                for (String next : subdirs) {
                    DocumentFile sub = dir.findFile(next);
                    if (sub == null || !sub.isDirectory()) { dir = root; reset = true; break; }
                    dir = sub;
                }
                ArrayList<DocumentFile> content = new ArrayList<>(Arrays.asList(dir.listFiles()));
                content.sort((a,b) -> {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    String aa = a.getName() == null ? "" : a.getName();
                    String bb = b.getName() == null ? "" : b.getName();
                    return aa.compareToIgnoreCase(bb);
                });
                DocumentFile target = dir;
                boolean resetPath = reset;
                runOnUiThread(() -> {
                    currentPhoneDirectory = target;
                    if (resetPath) { phonePath.clear(); prefs.edit().putString("phone_path", "[]").apply(); }
                    phoneEntries.clear(); phoneEntries.addAll(content);
                    phoneAdapter.notifyDataSetChanged();
                    renderPhonePath();
                    setBusy(false, "Телефон: " + content.size() + " объектов");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    currentPhoneDirectory = null;
                    phoneEntries.clear(); phoneAdapter.notifyDataSetChanged();
                    renderPhonePath();
                    setBusy(false, "Не открыть папку телефона: " + explain(e));
                });
            }
        });
    }

    private void confirmCopy(boolean computerToPhone) {
        if (busy || smb == null) { toast("Сначала подключи компьютер"); return; }
        if (currentPhoneDirectory == null) { toast("Сначала выбери папку телефона"); return; }
        int count = computerToPhone ? selectedPc.size() : selectedPhone.size();
        if (count == 0) {
            toast(computerToPhone ? "Отметь галочкой файлы на компьютере" : "Отметь галочкой файлы на телефоне");
            return;
        }
        String from = computerToPhone ? "Компьютер: " + pcLocation() : "Телефон: " + phoneLocation();
        String to = computerToPhone ? "Телефон: " + phoneLocation() : "Компьютер: " + pcLocation();
        new AlertDialog.Builder(this)
            .setTitle("Копировать " + count + " файл(ов)?")
            .setMessage("ОТКУДА\n" + from + "\n\nКУДА\n" + to
                + "\n\nИсходные файлы останутся на месте. Совпадающие имена не перезаписываются.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("КОПИРОВАТЬ", (dialog, choice) -> doCopy(computerToPhone))
            .show();
    }

    private void doCopy(boolean computerToPhone) {
        if (busy || smb == null || currentPhoneDirectory == null) return;
        ArrayList<String> remote = new ArrayList<>(pcPath);
        DocumentFile destPhone = currentPhoneDirectory;
        ArrayList<String> names = new ArrayList<>(selectedPc);
        ArrayList<Uri> urls = new ArrayList<>();
        for (DocumentFile file : phoneEntries) if (selectedPhone.contains(file.getUri().toString())) urls.add(file.getUri());
        int total = computerToPhone ? names.size() : urls.size();
        setBusy(true, "Копирую 0 из " + total + "…");
        worker.execute(() -> {
            int succeeded = 0;
            ArrayList<String> errors = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                try {
                    if (computerToPhone) smb.downloadIntoFolder(this, remote, names.get(i), destPhone);
                    else smb.upload(this, remote, urls.get(i));
                    succeeded++;
                } catch (Exception e) {
                    errors.add((computerToPhone ? names.get(i) : "Файл " + (i+1)) + ": " + explain(e));
                }
                int current = i + 1;
                runOnUiThread(() -> status.setText("Передача: " + current + " / " + total));
            }
            final int ok = succeeded;
            ArrayList<SmbClient.Entry> latestPc = null;
            ArrayList<DocumentFile> latestPhone = null;
            try { latestPc = new ArrayList<>(smb.list(remote)); } catch (Exception ignored) {}
            try { latestPhone = new ArrayList<>(Arrays.asList(destPhone.listFiles()));
                latestPhone.sort((a,b) -> {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    String aa = a.getName() == null ? "" : a.getName();
                    String bb = b.getName() == null ? "" : b.getName();
                    return aa.compareToIgnoreCase(bb);
                });
            } catch (Exception ignored) {}
            final ArrayList<SmbClient.Entry> pFiles = latestPc;
            final ArrayList<DocumentFile> tFiles = latestPhone;
            runOnUiThread(() -> {
                if (pFiles != null) { pcEntries.clear(); pcEntries.addAll(pFiles); pcAdapter.notifyDataSetChanged(); }
                if (tFiles != null) { phoneEntries.clear(); phoneEntries.addAll(tFiles); phoneAdapter.notifyDataSetChanged(); }
                if (computerToPhone) selectedPc.clear(); else selectedPhone.clear();
                setBusy(false, "Скопировано: " + ok + " из " + total + (errors.isEmpty() ? " ✓" : " • есть ошибки"));
                if (errors.isEmpty()) toast("Готово! " + ok + " файл(ов) скопировано");
                else new AlertDialog.Builder(this).setTitle("Результат: " + ok + "/" + total)
                    .setMessage(TextUtils.join("\n", errors)).setPositiveButton("Закрыть", null).show();
            });
        });
    }

    private void saveProfile() {
        if (busy) return;
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setText(host.getText().toString().trim() + " / " + share.getText().toString().trim());
        new AlertDialog.Builder(this).setTitle("Название компьютера").setView(name)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", (dialog, which) -> {
                try {
                    profiles.save(new SavedConnections.Profile(name.getText().toString().trim(),
                        host.getText().toString().trim(), share.getText().toString().trim(),
                        user.getText().toString().trim(), domain.getText().toString().trim(),
                        password.getText().toString()));
                    toast("Профиль сохранён");
                } catch (Exception e) { toast("Не сохранено: " + explain(e)); }
            }).show();
    }

    private void chooseProfile() {
        if (busy) return;
        try {
            String[] names = profiles.names();
            if (names.length == 0) { toast("Нет сохранённых компьютеров"); return; }
            new AlertDialog.Builder(this).setTitle("Мои компьютеры")
                .setItems(names, (d, n) -> {
                    try {
                        SavedConnections.Profile choice = profiles.load(n);
                        host.setText(choice.host); share.setText(choice.share);
                        user.setText(choice.user); domain.setText(choice.domain);
                        password.setText(choice.password); savePassword.setChecked(true);
                        connect();
                    } catch (Exception e) { toast("Профиль не открылся: " + explain(e)); }
                })
                .setNeutralButton("Удалить профиль", (d, n) -> {
                    new AlertDialog.Builder(this).setTitle("Какой профиль удалить?")
                        .setItems(names, (dlg, idx) -> new AlertDialog.Builder(this)
                            .setMessage("Удалить «" + names[idx] + "»?")
                            .setNegativeButton("Нет", null)
                            .setPositiveButton("Удалить", (dlg2, which) -> {
                                try { profiles.delete(names[idx]); toast("Удалено"); }
                                catch (Exception e) { toast(explain(e)); }
                            }).show())
                        .setNegativeButton("Отмена", null).show();
                }).setNegativeButton("Отмена", null).show();
        } catch (Exception e) { toast("Ошибка профилей: " + explain(e)); }
    }

    private void updateActions() {
        if (pcUpButton != null) pcUpButton.setEnabled(!busy && smb != null && !pcPath.isEmpty());
        if (phoneUpButton != null) phoneUpButton.setEnabled(!busy && !phonePath.isEmpty());
        if (phoneChooseButton != null) phoneChooseButton.setEnabled(!busy);
        if (connectButton != null) connectButton.setEnabled(!busy);
        if (toPhoneButton != null) {
            toPhoneButton.setText("↓  ПК → ТЕЛЕФОН   (" + selectedPc.size() + ")");
            toPhoneButton.setEnabled(!busy && smb != null && currentPhoneDirectory != null && !selectedPc.isEmpty());
        }
        if (toPcButton != null) {
            toPcButton.setText("↑  ТЕЛЕФОН → ПК   (" + selectedPhone.size() + ")");
            toPcButton.setEnabled(!busy && smb != null && currentPhoneDirectory != null && !selectedPhone.isEmpty());
        }
        if (toPhoneButton != null) toPhoneButton.setAlpha(toPhoneButton.isEnabled() ? 1f : 0.5f);
        if (toPcButton != null) toPcButton.setAlpha(toPcButton.isEnabled() ? 1f : 0.5f);
        if (pcCount != null) pcCount.setText(pcEntries.size() + " объектов  ·  отмечено: " + selectedPc.size() + " ✓");
        if (phoneCount != null) phoneCount.setText(phoneEntries.size() + " объектов  ·  выбрано: " + selectedPhone.size() + " ✓");
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        if (progress != null) progress.setVisibility(value ? View.VISIBLE : View.GONE);
        if (status != null) {
            status.setText(message);
            status.setTextColor(message.toLowerCase(Locale.ROOT).startsWith("ошиб") ? RED : MUTED);
        }
        updateActions();
    }

    private String pcLocation() {
        StringBuilder text = new StringBuilder("\\\\")
            .append(smb == null ? host.getText().toString().trim() : hostConnected)
            .append('\\').append(smb == null ? share.getText().toString().trim() : shareConnected);
        for (String s : pcPath) text.append('\\').append(s);
        return text.toString();
    }
    private String phoneLocation() {
        if (phoneTreeUri == null) return "Папка не выбрана";
        String root = "Выбранная папка";
        try { DocumentFile d = DocumentFile.fromTreeUri(this, phoneTreeUri);
            if (d != null && d.getName() != null) root = d.getName();
        } catch (Exception ignored) { }
        StringBuilder path = new StringBuilder(root);
        for (String s : phonePath) path.append(" / ").append(s);
        return path.toString();
    }
    private void renderPcPath() {
        if (pcPathView != null) pcPathView.setText(pcLocation());
        updateActions();
    }
    private void renderPhonePath() {
        if (phonePathView != null) phonePathView.setText(phoneLocation());
        updateActions();
    }

    private String pcKey(String h, String sh) { return "last_pc_path_" + h + "_" + sh; }
    private void persistPcPath() {
        if (!hostConnected.isEmpty() && !shareConnected.isEmpty())
            prefs.edit().putString(pcKey(hostConnected, shareConnected), pathJson(pcPath)).apply();
    }
    private static String pathJson(List<String> segments) { return new JSONArray(segments).toString(); }
    private static ArrayList<String> parsePath(String json) {
        ArrayList<String> path = new ArrayList<>();
        try {
            JSONArray data = new JSONArray(json);
            for (int i=0; i<data.length() && i<50; i++) {
                String value = data.getString(i);
                if (!value.isEmpty() && !value.equals("..") && !value.contains("/") && !value.contains("\\")) path.add(value);
            }
        } catch (Exception ignored) { }
        return path;
    }

    private LinearLayout vertical() { LinearLayout view = new LinearLayout(this); view.setOrientation(1); return view; }
    private LinearLayout horizontal() { LinearLayout view = new LinearLayout(this); view.setOrientation(0); return view; }
    private LinearLayout card(int accent) {
        LinearLayout panel = vertical();
        panel.setPadding(dp(13), dp(13), dp(13), dp(13));
        panel.setBackground(shape(Color.WHITE, 16, accent, 2));
        panel.setElevation(dp(2));
        return panel;
    }
    private LinearLayout.LayoutParams cardLp(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(top); return lp;
    }
    private LinearLayout.LayoutParams listLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(216));
        lp.topMargin = dp(8); return lp;
    }
    private GradientDrawable shape(int fill, int corner, int border, int borderWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill); drawable.setCornerRadius(dp(corner));
        if (borderWidth > 0) drawable.setStroke(dp(borderWidth), border);
        return drawable;
    }
    private TextView label(String text, int size, int color, boolean strong) {
        TextView tv = new TextView(this);
        tv.setText(text); tv.setTextSize(size); tv.setTextColor(color);
        if (strong) tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return tv;
    }
    private EditText input(LinearLayout parent, String hint, String value) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true); edit.setText(value); edit.setHint(hint);
        edit.setTextSize(14); edit.setTextColor(INK); edit.setHintTextColor(MUTED);
        edit.setPadding(dp(7), dp(8), dp(7), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(47));
        lp.topMargin = dp(4); parent.addView(edit, lp); return edit;
    }
    private Button button(String text, int color) {
        Button btn = new Button(this);
        btn.setText(text); btn.setAllCaps(false); btn.setTextColor(Color.WHITE);
        btn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        btn.setTextSize(14);
        btn.setBackground(shape(color, 12, 0, 0));
        btn.setPadding(dp(6), 0, dp(6), 0);
        return btn;
    }
    private ListView listView() {
        ListView view = new ListView(this);
        view.setDivider(null); view.setDividerHeight(dp(5));
        view.setCacheColorHint(Color.TRANSPARENT);
        view.setVerticalScrollBarEnabled(true);
        view.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE)
                v.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });
        return view;
    }
    private View fileRow(String name, boolean folder, long length,
                         boolean checked, int accent, Runnable tapped) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(55));
        row.setPadding(dp(10), dp(7), dp(9), dp(7));
        row.setBackground(shape(checked ? (accent == BLUE ? LIGHT_BLUE : LIGHT_TEAL) : BG,
            11, checked ? accent : 0, checked ? 1 : 0));
        TextView type = label(folder ? "📁" : "📄", 22, INK, false);
        row.addView(type);
        LinearLayout texts = vertical();
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
        tp.leftMargin = dp(9); row.addView(texts, tp);
        TextView nameView = label(name, 14, INK, checked);
        nameView.setMaxLines(2); nameView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(nameView);
        TextView sub = label(folder ? "Открыть папку" : humanSize(length) + "  ·  нажми для выбора", 11, MUTED, false);
        texts.addView(sub);
        TextView check = label(folder ? "›" : (checked ? "☑" : "□"), 24, accent, true);
        check.setGravity(Gravity.CENTER);
        row.addView(check, new LinearLayout.LayoutParams(dp(35), dp(38)));
        row.setOnClickListener(v -> tapped.run());
        return row;
    }
    private int dp(int px) { return Math.round(px * getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private static String explain(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) return e.getClass().getSimpleName();
        return m.length() > 190 ? m.substring(0, 190) + "…" : m;
    }
    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " Б";
        if (bytes < 1024*1024) return String.format(Locale.ROOT, "%.1f КБ", bytes/1024.0);
        if (bytes < 1024L*1024*1024) return String.format(Locale.ROOT, "%.1f МБ", bytes/1048576.0);
        return String.format(Locale.ROOT, "%.2f ГБ", bytes/1073741824.0);
    }
    @Override public void onBackPressed() {
        if (!busy && phoneTreeUri != null && !phonePath.isEmpty()) {
            phonePath.remove(phonePath.size()-1);
            prefs.edit().putString("phone_path", pathJson(phonePath)).apply();
            refreshPhone();
        } else if (!busy && !pcPath.isEmpty()) {
            pcPath.remove(pcPath.size()-1); openPcFolder();
        } else super.onBackPressed();
    }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
