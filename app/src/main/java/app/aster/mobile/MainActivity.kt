package app.aster.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import java.io.ByteArrayInputStream
import java.net.URI

/** Native controls own navigation; arbitrary websites receive no JavaScript interface. */
class MainActivity : Activity() {
    private data class Tab(val web: WebView, var title: String = "New tab", var address: String = "", var generation: Long = 0, var failed: Boolean = false)
    private lateinit var store: BrowserStore
    private lateinit var shield: MobileShield
    private lateinit var downloads: DownloadGuard
    private lateinit var layout: LinearLayout
    private lateinit var surface: FrameLayout
    private lateinit var address: EditText
    private lateinit var security: Button
    private lateinit var progress: ProgressBar
    private lateinit var back: Button
    private lateinit var forward: Button
    private lateinit var tabButton: Button
    private val tabs = mutableListOf<Tab>()
    private var active = -1
    private var safeBrowsingReady = false
    private var blockDialog: AlertDialog? = null
    private var importCategory: MobileShield.Category? = null
    private var reportToExport: String? = null
    private val disabledJavascript = mutableSetOf<String>()
    private val deniedDownloads = mutableSetOf<String>()
    private var fullView: View? = null
    private var fullCallback: WebChromeClient.CustomViewCallback? = null
    private val current: Tab? get() = tabs.getOrNull(active)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
    private fun button(text: String, description: String, action: () -> Unit) = Button(this).apply {
        this.text = text; contentDescription = description; minWidth = 0; minimumWidth = 0
        setPadding(dp(8), 0, dp(8), 0); setOnClickListener { action() }
    }
    private fun notice(title: String, message: String) { if (!isFinishing && !isDestroyed) AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show() }
    private fun attempt(action: () -> Unit) { try { action() } catch (e: Exception) { notice("Aster", e.message ?: "Action could not be completed") } }
    private fun save() { attempt { store.save() } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BrowserStore(this); shield = MobileShield(); applyShield()
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        downloads = DownloadGuard(this, shield)
        createInterface()
        WebView.startSafeBrowsing(this) { ready -> safeBrowsingReady = ready; refreshControls() }
        val restored = store.session.toList()
        if (restored.isEmpty()) newTab() else restored.take(12).forEach { newTab(it) }
        if (intent.action == Intent.ACTION_VIEW) intent.dataString?.let { if (NavigationPolicy.isWebAddress(it)) navigate(it) }
        if (store.warning.isNotBlank()) notice("Profile recovery", store.warning)
    }
    private fun createInterface() {
        layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        val top = row()
        security = button("◈", "Site Control") { siteControl() }
        address = EditText(this).apply {
            setSingleLine(); hint = "Search or enter address"; imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP) {
                    attempt { navigate(NavigationPolicy.resolve(text.toString(), store.search)) }
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
                    clearFocus(); true
                } else false
            }
        }
        top.addView(security, LinearLayout.LayoutParams(dp(48), dp(52)))
        top.addView(address, LinearLayout.LayoutParams(0, dp(52), 1f))
        top.addView(button("⋮", "Browser menu") { menu() }, LinearLayout.LayoutParams(dp(48), dp(52)))
        layout.addView(top)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        layout.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
        surface = FrameLayout(this); layout.addView(surface, LinearLayout.LayoutParams(-1, 0, 1f))
        val bottom = row()
        back = button("‹", "Back") { current?.web?.takeIf { it.canGoBack() }?.goBack() }
        forward = button("›", "Forward") { current?.web?.takeIf { it.canGoForward() }?.goForward() }
        val reload = button("↻", "Reload") { current?.web?.reload() }
        val home = button("⌂", "Home") { if (store.home.isBlank()) showHome() else navigate(store.home) }
        tabButton = button("1", "Open tabs") { tabManager() }
        val add = button("+", "New tab") { newTab() }
        listOf(back, forward, reload, home, tabButton, add).forEach { bottom.addView(it, LinearLayout.LayoutParams(0, dp(50), 1f)) }
        layout.addView(bottom); setContentView(layout); applyTheme()
    }
    private fun applyTheme() {
        val dark = store.theme == "dark" || store.theme == "system" && resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val bg = if (dark) Color.rgb(18, 22, 35) else Color.rgb(242, 244, 251)
        val fg = if (dark) Color.rgb(236, 232, 255) else Color.rgb(30, 34, 50)
        layout.setBackgroundColor(bg); address.setTextColor(fg); address.setHintTextColor(if (dark) Color.LTGRAY else Color.DKGRAY)
        window.statusBarColor = bg; window.navigationBarColor = bg
    }
    private fun makeWebView(): WebView = WebView(this).apply {
        settings.javaScriptEnabled = true; settings.domStorageEnabled = true
        settings.allowFileAccess = false; settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false; settings.allowUniversalAccessFromFileURLs = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.safeBrowsingEnabled = true; settings.mediaPlaybackRequiresUserGesture = true
        settings.javaScriptCanOpenWindowsAutomatically = false; settings.setSupportMultipleWindows(false)
        settings.useWideViewPort = true; settings.loadWithOverviewMode = true; settings.builtInZoomControls = true; settings.displayZoomControls = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, store.thirdPartyCookies)
        setOnLongClickListener {
            val result = hitTestResult
            if (result.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                val link = result.extra
                if (NavigationPolicy.isWebAddress(link)) { AlertDialog.Builder(this@MainActivity).setTitle("Link actions").setItems(arrayOf("Open in new tab", "Cancel")) { _, which -> if (which == 0) newTab(link) }.show(); true } else false
            } else false
        }
    }
    private fun newTab(url: String? = null) {
        if (tabs.size >= 12) { notice("Tab limit", "This mobile preview supports 12 tabs. Close a tab first."); return }
        val web = makeWebView(); val tab = Tab(web); tabs += tab; active = tabs.lastIndex
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val value = request.url.toString()
                if (!NavigationPolicy.isWebAddress(value)) { if (request.isForMainFrame) notice("Unsupported link", "Only HTTP and HTTPS websites are allowed. No external application was launched."); return true }
                if (request.isForMainFrame && blocked(tab, value)) return true
                return false
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!NavigationPolicy.isWebAddress(request.url.toString())) return null
                if (shield.decide(request.url.toString(), request.isForMainFrame).blocked) return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked by Mobile Shield", emptyMap(), ByteArrayInputStream("Blocked by current domain rules".toByteArray()))
                return null
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                tab.generation++; tab.failed = false
                if (url != null && NavigationPolicy.isWebAddress(url)) {
                    tab.address = url
                    if (shield.decide(url, true).blocked) { view.stopLoading(); tab.failed = true; if (tab === current) blocked(tab, url); return }
                    view.settings.javaScriptEnabled = NavigationPolicy.origin(url) !in disabledJavascript
                }
                if (tab === current) refreshControls()
            }
            override fun onPageFinished(view: WebView, url: String?) {
                if (url != null && NavigationPolicy.isWebAddress(url) && !tab.failed) {
                    tab.address = url; tab.title = view.title ?: url; attempt { store.visit(url, tab.title) }; persistSession()
                }
                if (tab === current) refreshControls()
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel(); tab.failed = true
                if (tab === current) notice("ASTER SECURITY WARNING", "Certificate validation failed. The connection was cancelled; there is no bypass.")
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) { if (request.isForMainFrame) { tab.failed = true; if (tab === current) refreshControls() } }
            override fun onSafeBrowsingHit(view: WebView, request: WebResourceRequest, threatType: Int, callback: SafeBrowsingResponse) {
                tab.failed = true; callback.backToSafety(true)
                if (tab === current) notice("ASTER SECURITY WARNING", "Android WebView Safe Browsing blocked a reported threat. No destination is guaranteed safe by these checks.")
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, value: Int) { if (tab === current) { progress.progress = value; progress.visibility = if (value < 100) View.VISIBLE else View.INVISIBLE } }
            override fun onReceivedTitle(view: WebView, title: String?) { if (NavigationPolicy.isWebAddress(view.url)) tab.title = title?.take(300) ?: tab.address }
            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
                if (tab === current) notice("Site permission blocked", "Camera, microphone and protected media permissions are not enabled in this preview. No Android device permission was requested.")
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false); notice("Location blocked", "Location access is not enabled in this preview.") }
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullView != null) { callback.onCustomViewHidden(); return }
                fullView = view; fullCallback = callback; (window.decorView as ViewGroup).addView(view, ViewGroup.LayoutParams(-1, -1))
            }
            override fun onHideCustomView() { hideFullscreen() }
        }
        web.setDownloadListener { link, agent, disposition, mime, size ->
            if (tab !== current) return@setDownloadListener
            if (runCatching { NavigationPolicy.origin(tab.address) in deniedDownloads }.getOrDefault(false)) notice("Download blocked", "Downloads are blocked for this origin during this session.")
            else downloads.request(link, disposition, mime, size, agent)
        }
        showTab(tab)
        if (url != null) attempt { navigate(NavigationPolicy.webAddress(url)) } else showHome()
        refreshControls()
    }
    private fun showTab(tab: Tab) { surface.removeAllViews(); (tab.web.parent as? ViewGroup)?.removeView(tab.web); surface.addView(tab.web, FrameLayout.LayoutParams(-1, -1)); refreshControls() }
    private fun navigate(raw: String, explicitHttp: Boolean = false) {
        val tab = current ?: return
        var url = NavigationPolicy.webAddress(raw)
        if (!explicitHttp && store.httpsFirst && url.startsWith("http://") && !NavigationPolicy.secureForPermissions(url)) url = "https://" + url.removePrefix("http://")
        if (blocked(tab, url)) return
        tab.web.settings.javaScriptEnabled = NavigationPolicy.origin(url) !in disabledJavascript
        tab.address = url; tab.failed = false; showTab(tab); tab.web.loadUrl(url)
    }
    private fun blocked(tab: Tab, url: String): Boolean {
        val decision = shield.decide(url, true)
        if (!decision.blocked) return false
        if (tab !== current) return true
        blockDialog?.dismiss()
        val epoch = tab.generation
        blockDialog = AlertDialog.Builder(this).setTitle(if (decision.category == MobileShield.Category.ADULT) "ASTER CONTENT PROTECTION" else "ASTER SECURITY WARNING")
            .setMessage("Blocked by your ${decision.category} domain rules: ${decision.matchedDomain}\n\nSource: ${runCatching { URI(url).host }.getOrDefault("Unknown")}\nThese rules may contain mistakes and cannot classify every site.")
            .setNegativeButton("Back to safety") { _, _ -> showHome() }
            .setPositiveButton("Allow domain for this session") { _, _ -> if (current === tab && tab.generation == epoch) attempt { shield.setExceptions(shield.exceptionDomains() + URI(url).host); navigate(url, true) } }.show()
        return true
    }
    private fun refreshControls() {
        if (!::address.isInitialized) return
        val tab = current
        if (!address.hasFocus()) address.setText(tab?.address ?: "")
        security.text = if (tab?.address?.startsWith("https://") == true && !tab.failed && progress.progress == 100) "●" else "◈"
        security.contentDescription = "Site Control — HTTPS is transport protection, not a safety guarantee"
        back.isEnabled = tab?.web?.canGoBack() == true; forward.isEnabled = tab?.web?.canGoForward() == true; tabButton.text = tabs.size.toString()
    }
    private fun showHome() {
        val tab = current ?: return
        tab.web.stopLoading(); tab.generation++; tab.address = ""; tab.title = "New tab"
        tab.web.loadUrl("about:blank"); surface.removeAllViews()
        val scroll = ScrollView(this); val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(28), dp(24), dp(24)) }
        content.addView(TextView(this).apply { text = "✦  aster"; textSize = 34f; setTextColor(Color.rgb(162, 143, 255)) })
        content.addView(TextView(this).apply { text = "A little space. Endless possibilities.\n\n${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}"; textSize = 17f; setTextColor(Color.GRAY) })
        val search = EditText(this).apply { hint = "Search the web"; setSingleLine(); inputType = android.text.InputType.TYPE_CLASS_TEXT }
        content.addView(search); content.addView(button("Explore", "Search") { attempt { navigate(NavigationPolicy.resolve(search.text.toString(), store.search)) } })
        content.addView(button("Bookmarks", "Open bookmarks") { showEntries(false) })
        store.bookmarks.take(8).forEach { entry -> content.addView(button(entry.title.ifBlank { entry.url }, "Open bookmark") { navigate(entry.url) }) }
        content.addView(TextView(this).apply { text = "Native WebView browser preview. Security lists are not preloaded; configure Mobile Shield. No sync or provider VPN is connected."; setTextColor(Color.GRAY) })
        scroll.addView(content); surface.addView(scroll); persistSession(); refreshControls()
    }
    private fun persistSession() { store.session.clear(); store.session.addAll(tabs.map { it.address }.filter { NavigationPolicy.isWebAddress(it) }); save() }
    private fun tabManager() {
        val labels = tabs.mapIndexed { i, t -> "${if (i == active) "● " else ""}${t.title.take(60)}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Tabs (${tabs.size}/12)").setItems(labels) { _, index -> active = index; if (current!!.address.isBlank()) showHome() else showTab(current!!) }
            .setNeutralButton("Close current") { _, _ -> closeCurrent() }.setPositiveButton("New tab") { _, _ -> newTab() }.setNegativeButton("Cancel", null).show()
    }
    private fun closeCurrent() {
        val tab = current ?: return; tabs.removeAt(active); surface.removeAllViews(); tab.web.stopLoading(); tab.web.destroy()
        if (tabs.isEmpty()) { active = -1; newTab() } else { active = active.coerceAtMost(tabs.lastIndex); if (current!!.address.isBlank()) showHome() else showTab(current!!) }
        persistSession()
    }
    private fun menu() {
        val actions = arrayOf("Bookmark this page", "Bookmarks", "History", "Downloads / Download Guard", "Settings / Mobile Shield", "Private browsing availability", "About Aster")
        AlertDialog.Builder(this).setTitle("Aster").setItems(actions) { _, which -> when (which) {
            0 -> bookmarkCurrent(); 1 -> showEntries(false); 2 -> showEntries(true); 3 -> downloadDashboard(); 4 -> settings()
            5 -> notice("Private browsing", "Private browsing is not enabled in this build. Android WebView storage is process-scoped; this app will not falsely label the shared session private. Use a separate browser until isolated-process private mode is implemented and tested.")
            else -> notice("Aster Mobile 4.0 preview", "Native Android WebView. No website JavaScript bridge. System WebView version: ${WebView.getCurrentWebViewPackage()?.versionName ?: "Unknown"}\n\nNo production sync, VPN, password manager or antivirus provider is configured. Local download checks never guarantee safety.")
        } }.show()
    }
    private fun bookmarkCurrent() { val tab=current ?: return; if (!NavigationPolicy.isWebAddress(tab.address)) return; val form=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(20),0,dp(20),0) };val title=EditText(this).apply{setText(tab.title);hint="Name"};val folder=EditText(this).apply{hint="Folder"};form.addView(title);form.addView(folder);AlertDialog.Builder(this).setTitle("Save bookmark").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Save"){_,_->attempt{store.bookmark(tab.address,title.text.toString(),folder.text.toString())}}.show() }
    private fun showEntries(history: Boolean) { val entries=(if(history)store.history else store.bookmarks).toList();if(entries.isEmpty()){notice(if(history)"History" else "Bookmarks","No entries yet.");return};AlertDialog.Builder(this).setTitle(if(history)"History" else "Bookmarks").setItems(entries.map{"${it.folder.takeIf(String::isNotBlank)?.plus(" / ") ?: ""}${it.title}"}.toTypedArray()){_,i->val entry=entries[i];AlertDialog.Builder(this).setTitle(entry.title).setMessage(entry.url).setPositiveButton("Open"){_,_->navigate(entry.url)}.setNeutralButton("Delete"){_,_->(if(history)store.history else store.bookmarks).removeAll{it.id==entry.id};save()}.setNegativeButton("Cancel",null).show()}.setNegativeButton("Close",null).show() }
    private fun siteControl() {val tab=current ?: return;val origin=runCatching{NavigationPolicy.origin(tab.address)}.getOrNull() ?: return notice("Site Control","Open a website first.");val options=arrayOf("JavaScript for this origin (session only)","Downloads for this origin (session only)","Third-party cookies (all tabs)");val selected=booleanArrayOf(origin !in disabledJavascript,origin !in deniedDownloads,store.thirdPartyCookies);AlertDialog.Builder(this).setTitle("ASTER SITE CONTROL").setMessage(null).setMultiChoiceItems(options,selected){_,i,on->when(i){0->{if(on)disabledJavascript.remove(origin)else disabledJavascript.add(origin);tabs.filter{runCatching{NavigationPolicy.origin(it.address)==origin}.getOrDefault(false)}.forEach{it.web.settings.javaScriptEnabled=on}};1->if(on)deniedDownloads.remove(origin)else deniedDownloads.add(origin);2->{store.thirdPartyCookies=on;tabs.forEach{CookieManager.getInstance().setAcceptThirdPartyCookies(it.web,on)};save()}}}.setPositiveButton("Details"){_,_->notice("Site information","$origin\n${if(tab.address.startsWith("https:")&&!tab.failed)"HTTPS requested; Chromium validates certificates" else "HTTP or failed/unverified transport"}\nSafe Browsing initialization: $safeBrowsingReady\nCamera/microphone/location: blocked in this preview\nJavaScript changes affect current/future execution, not a retroactive undo. Reload to apply consistently.\nNo certificate reputation guarantee.")}.setNegativeButton("Close",null).show() }
    private fun settings() { val options=arrayOf("Theme","Search engine","Homepage URL","HTTPS-first","Third-party cookies","Mobile Shield","Clear browsing data");AlertDialog.Builder(this).setTitle("Settings").setItems(options){_,i->when(i){0->choose("Theme",arrayOf("dark","light","system")){store.theme=it;save();applyTheme()};1->choose("Search engine",arrayOf("google","bing","duckduckgo")){store.search=it;save()};2->textInput("Homepage (empty means Aster home)",store.home){store.home=if(it.isBlank())"" else NavigationPolicy.webAddress(it);save()};3->{store.httpsFirst=!store.httpsFirst;save();notice("HTTPS-first",if(store.httpsFirst)"Enabled for typed navigation; redirects retain native policy. No TLS bypass." else "Disabled for explicitly entered HTTP URLs.")};4->{store.thirdPartyCookies=!store.thirdPartyCookies;tabs.forEach{CookieManager.getInstance().setAcceptThirdPartyCookies(it.web,store.thirdPartyCookies)};save();notice("Third-party cookies",if(store.thirdPartyCookies)"Allowed" else "Blocked")};5->shieldSettings();6->AlertDialog.Builder(this).setTitle("Clear browsing data?").setMessage("Cookies, all website storage, HTTP cache and history are cleared. Bookmarks and downloaded files remain.").setNegativeButton("Cancel",null).setPositiveButton("Clear"){_,_->tabs.forEach{it.web.stopLoading();it.web.clearCache(true);it.web.clearHistory()};CookieManager.getInstance().removeAllCookies{CookieManager.getInstance().flush()};WebStorage.getInstance().deleteAllData();store.history.clear();save()}.show()}}.setNegativeButton("Close",null).show() }
    private fun choose(title:String,items:Array<String>,action:(String)->Unit){AlertDialog.Builder(this).setTitle(title).setItems(items){_,i->attempt{action(items[i])}}.setNegativeButton("Cancel",null).show()}
    private fun textInput(title:String,value:String,action:(String)->Unit){val input=EditText(this).apply{setText(value);setSingleLine()};AlertDialog.Builder(this).setTitle(title).setView(input).setNegativeButton("Cancel",null).setPositiveButton("Save"){_,_->attempt{action(input.text.toString())}}.show()}
    private fun applyShield(){shield.mode=MobileShield.Mode.valueOf(store.shieldMode);shield.threatProtection=store.threatProtection;shield.adultFiltering=store.adultFiltering;shield.trackerProtection=store.trackerProtection;for(c in MobileShield.Category.entries)shield.replace(c,store.domains[c.name]?:emptyList());shield.setExceptions(store.exceptions)}
    private fun shieldSettings(){val labels=arrayOf("Mode: ${store.shieldMode}","Threat rules: ${shield.count(MobileShield.Category.THREAT)} (${store.threatProtection})","Adult rules: ${shield.count(MobileShield.Category.ADULT)} (${store.adultFiltering})","Tracker rules: ${shield.count(MobileShield.Category.TRACKER)} (${store.trackerProtection})","Import domain list","Exceptions","Protection limits");AlertDialog.Builder(this).setTitle("ASTER MOBILE SHIELD").setItems(labels){_,i->when(i){0->choose("Mode",MobileShield.Mode.entries.map{it.name}.toTypedArray()){store.shieldMode=it;applyShield();save()};1->{store.threatProtection=!store.threatProtection;applyShield();save()};2->{store.adultFiltering=!store.adultFiltering;applyShield();save()};3->{store.trackerProtection=!store.trackerProtection;applyShield();save()};4->choose("List category",MobileShield.Category.entries.map{it.name}.toTypedArray()){importCategory=MobileShield.Category.valueOf(it);startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="text/*";addCategory(Intent.CATEGORY_OPENABLE)},91)};5->textInput("Allowed domains (comma separated)",store.exceptions.joinToString(",")){store.exceptions=it.split(',').map(String::trim).filter(String::isNotEmpty).map { MobileShield.domain(it) };applyShield();save()};6->notice("Coverage and limitations","System WebView Safe Browsing: initialized=$safeBrowsingReady. It depends on the installed WebView and Google/system services. No detection guarantee.\n\nLocal domain lists must be imported from a source you trust; none are preloaded. No adult-content classification is claimed until you supply rules. STANDARD blocks enabled threat/adult domain rules. ENHANCED also blocks tracker subresources. STRICT additionally blocks top-level tracker domains. These modes are not independent reputation feeds. Secure DNS is controlled by Android settings, not a fake per-app switch. Exceptions can reduce protection.")}}.setNegativeButton("Close",null).show()}
    private fun downloadDashboard(){if(downloads.items.isEmpty()){notice("Download Guard","No downloads yet.");return};val items=downloads.items.toList();AlertDialog.Builder(this).setTitle("Download Guard").setItems(items.map{"${it.name}\n${it.state} · ${it.bytes}${if(it.total>=0)"/${it.total}" else ""} bytes"}.toTypedArray()){_,i->val item=items[i];val actions=if(item.busy.get())arrayOf("Cancel transfer","Report") else arrayOf("Report","Repeat local checks","Export report","Delete file and record");AlertDialog.Builder(this).setTitle(item.name).setItems(actions){_,which->attempt{when(actions[which]){"Cancel transfer"->downloads.cancel(item);"Report"->notice("Inspection report",downloads.reportText(item));"Repeat local checks"->downloads.recheck(item);"Export report"->{reportToExport=downloads.reportText(item);startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{type="text/plain";addCategory(Intent.CATEGORY_OPENABLE);putExtra(Intent.EXTRA_TITLE,"aster-download-report.txt")},92)};"Delete file and record"->AlertDialog.Builder(this).setTitle("Delete download?").setNegativeButton("Cancel",null).setPositiveButton("Delete"){_,_->attempt{downloads.remove(item)}}.show()}}}.show()}.setNegativeButton("Close",null).show()}
    @Deprecated("Platform callback") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(resultCode!=RESULT_OK)return;val uri=data?.data?:return;attempt{if(requestCode==91){val category=importCategory?:return@attempt;val input=contentResolver.openInputStream(uri)?:error("Cannot read list");val bytes=input.use{it.readBytesLimited(1024*1024)};val lines=bytes.toString(Charsets.UTF_8).lineSequence().map(String::trim).filter{it.isNotBlank()&&!it.startsWith('#')&&!it.startsWith('!')}.take(10001).toList();require(lines.size<=10000){"List exceeds 10,000 domains"};val parsed=lines.map(MobileShield::domain);store.domains[category.name]=parsed;applyShield();save();notice("List imported","${parsed.distinct().size} domains. These are supplied rules, not verified classifications.")}else if(requestCode==92){val report=reportToExport?:return@attempt;contentResolver.openOutputStream(uri)?.use{it.write(report.toByteArray())};reportToExport=null}}}
    private fun java.io.InputStream.readBytesLimited(limit:Int):ByteArray{val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=read(buffer);if(n<0)break;require(output.size()+n<=limit){"File exceeds import limit"};output.write(buffer,0,n)};return output.toByteArray()}
    private fun hideFullscreen(){fullView?.let{(it.parent as? ViewGroup)?.removeView(it)};fullView=null;fullCallback?.onCustomViewHidden();fullCallback=null}
    @Deprecated("Platform navigation") override fun onBackPressed(){if(fullView!=null){hideFullscreen();return};val web=current?.web;if(web?.canGoBack()==true)web.goBack()else showHome()}
    override fun onPause(){super.onPause();CookieManager.getInstance().flush();persistSession();tabs.forEach{it.web.onPause()}}
    override fun onResume(){super.onResume();tabs.forEach{it.web.onResume()}}
    override fun onConfigurationChanged(newConfig:Configuration){super.onConfigurationChanged(newConfig);applyTheme()}
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);if(intent.action==Intent.ACTION_VIEW)intent.dataString?.let{if(NavigationPolicy.isWebAddress(it))attempt{newTab(it)}}}
    override fun onDestroy(){blockDialog?.dismiss();downloads.dispose();surface.removeAllViews();tabs.forEach{it.web.stopLoading();it.web.destroy()};tabs.clear();super.onDestroy()}
}

