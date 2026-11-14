package io.parkflow.probe

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import android.os.SystemClock
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.UUID

class ProbeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var manager: NotificationManager
    private var helper: NaviHelper? = null
    private var enabled = false
    private var connected = false
    private var activeToken: String? = null
    private var cardTarget: DemoTarget? = null
    private var pending: Runnable? = null
    private val session = RecommendationSession()
    private var cardLot: ParkingLot? = null
    private var engine: RecommendationEngine? = null
    private var requestedRead: Pair<String, Long>? = null
    private val destinationRead = DestinationRead()
    private var gearMonitor: GearMonitor? = null
    private var autoEnabled = false
    private var minutes = 60
    private var generation = 0L
    private var postedParkingToken: String? = null
    private val parkingRefresh = object : Runnable {
        override fun run() {
            if (!enabled) return
            syncParkingNotification()
            handler.postDelayed(this, 500)
        }
    }
    private fun clearCandidates() {
        ProbeState.recommendationActions.invalidate()
        ProbeState.result = if (session.destination == null) ComparisonResult.NO_DESTINATION else ComparisonResult.SEARCHING
        ProbeState.snapshot = CandidateSnapshot(++generation, session.destination?.identity, minutes, emptyList())
        ProbeState.candidates = ""
    }
    private fun updateSelection() {
        ProbeState.selection = ProbeState.selection?.observe(session.destination, SystemClock.elapsedRealtime())
        ProbeState.gearContext()
    }
    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = onMain {
            record("onRouteStarted: $routeStartInfo; pending card invalidated")
            cancelCard(); clearCandidates()
        }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onRouteEnded() = onMain {
            record("onRouteEnded(legacy) received; event-driven requested read")
            readDestination("event:onRouteEnded(legacy)")
        }
        override fun onDestinationChanged() = onMain {
            record("onDestinationChanged received → requested read")
            // Invalidate immediately: the old destination is no longer safe for an action.
            ProbeState.selection = ProbeState.selection?.destinationChanging(SystemClock.elapsedRealtime())
            if (ProbeState.selection == null) session.clearSelection()
            ProbeState.gearContext()
            cancelCard(); session.invalidate(); clearCandidates()
            readDestination("onDestinationChanged")
        }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) = onMain {
            val d = destinationInfo
            val request = requestedRead
            val source = if (request != null && SystemClock.elapsedRealtime() - request.second < 5000)
                "requested-read window (${request.first}); SDK has no request ID, push/response ambiguous" else "unsolicited (no recent requested read)"
            requestedRead = null
            val notify = destinationRead.received(); ProbeState.destinationRead = destinationRead.status
            ProbeState.failure = FlowFailure.NONE
            ProbeState.destination = "목적지: ${d.name}\n${d.latitude}, ${d.longitude}\n${d.address}"
            record("onDestinationInfo [$source]: ${ProbeState.destination}; POI=${d.poiId}")
            val next = DestinationContext(d.name, "${d.poiProvider}:${d.poiId}:${d.poiSubId.orEmpty()}", d.latitude, d.longitude)
            val changed = session.observe(next, SystemClock.elapsedRealtime())
            ProbeState.destinationName = session.destination?.name
            ProbeState.flowError = null
            updateSelection()
            if (changed || session.destination == null) {
                cancelCard()
                clearCandidates()
                if (session.destination == null) {
                    ProbeState.recommendation = "목적지 없음 · 추천 취소"
                    record("Empty/invalid destination; pending work and card invalidated")
                } else recommend(notify)
            } else {
                if (ProbeState.snapshot.items.isEmpty() && session.destination != null) recommend(false)
                record("Duplicate destination; no automatic repost")
            }
        }
        override fun onRoutingCompleted() = onMain {
            record("onRoutingCompleted received; request association unavailable")
            readDestination("event:onRoutingCompleted")
        }
        override fun onError(errorCode: NaviErrorCode) = onMain {
            session.clearSelection(); ProbeState.selection = null; ProbeState.gearContext()
            cancelCard(); clearCandidates()
            destinationRead.received(); ProbeState.destinationRead = destinationRead.status; requestedRead = null
            ProbeState.failure = FlowFailure.NAVIGATION
            ProbeState.flowError = "내비 안내를 완료하지 못했어요. 목적지를 다시 확인해주세요."
            record("onError: $errorCode (SDK event; request association unavailable); selection cleared; explicit retry available")
        }
        override fun onRouteCancelled() = onMain {
            record("onRouteCancelled; destination/card cleared")
            destinationRead.received(); ProbeState.destinationRead = destinationRead.status
            ProbeState.failure = FlowFailure.NONE; ProbeState.flowError = null
            requestedRead = null; session.observe(null, SystemClock.elapsedRealtime()); updateSelection(); clearCandidates(); cancelCard()
            ProbeState.destinationName = null; ProbeState.destination = "목적지 없음"; ProbeState.candidates = ""; ProbeState.recommendation = "안내 취소 · 추천 취소"
        }
    }
    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(NotificationManager::class.java)
        autoEnabled = getSharedPreferences("probe", MODE_PRIVATE).getBoolean("auto", false)
        minutes = getSharedPreferences("probe", MODE_PRIVATE).getInt("minutes", 60).takeIf { it in stayChoices } ?: 60
        session.setAutomatic(autoEnabled)
        loadParking()
        manager.createNotificationChannel(NotificationChannel(STATUS_CHANNEL, "Listener status", NotificationManager.IMPORTANCE_LOW).apply {
            description = "User-enabled navigation SDK listener"; setSound(null, null)
        })
        manager.createNotificationChannel(NotificationChannel(PARKING_CHANNEL, "주차 기록 확인", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "P 전환 후 직접 주차 여부를 확인해 저장 · 실제 도착 판단 아님"
        })
        manager.createNotificationChannel(NotificationChannel(CARD_CHANNEL, "주차 추천 (공공자료·데모)", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "공공자료 자동 추천과 수동 DEMO · 팝업은 차량 정책에 따름"
        })
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == START) {
            if (!enabled) startListener()
        } else if (!enabled) {
            record("Ignored ${intent?.action}: listener not explicitly active; enable again from app")
            stopSelf()
        } else when (intent?.action) {
            AUTO -> {
                autoEnabled = getSharedPreferences("probe", MODE_PRIVATE).getBoolean("auto", false)
                session.setAutomatic(autoEnabled); cancelCard(); clearCandidates()
                ProbeState.recommendation = if (autoEnabled) "자동 추천 켜짐 · 목적지 확인 중" else "자동 추천 꺼짐"
                record("Automatic setting=$autoEnabled; pending work/card invalidated")
                if (autoEnabled) { session.observe(null, SystemClock.elapsedRealtime()); readDestination("automatic enabled by user") }
            }
            POST -> { cancelPending(); postCard(DemoTarget.load(this)) }
            DELAY -> {
                cancelPending()
                val target = DemoTarget.load(this)
                pending = Runnable { pending = null; if (enabled) postCard(target) }
                handler.postDelayed(pending!!, 10_000)
                record("Recommendation scheduled in 10 s; switch to navigation now")
            }
            DURATION -> {
                minutes = getSharedPreferences("probe", MODE_PRIVATE).getInt("minutes", 60).takeIf { it in stayChoices } ?: 60
                cancelCard(); clearCandidates(); recommend(false)
            }
            SELECT -> {
                val lot = ProbeState.snapshot.resolve(intent.getStringExtra("lotId"), intent.getLongExtra("generation", -1), session.destination?.identity, minutes)
                if (lot == null) record("Ignored stale candidate context / id")
                else {
                    cancelCard(); requestParking(lot)
                    val tappedAt = intent.getLongExtra("foregroundTappedAt", -1)
                    if (tappedAt >= 0 && SystemClock.elapsedRealtime() - tappedAt in 0 until 8_000) foregroundNavigation()
                }
            }
            FOREGROUND -> foregroundNavigation()
            RETRY -> {
                session.clearSelection(); ProbeState.selection = null; ProbeState.gearContext()
                cancelCard(); clearCandidates()
                readDestination("explicit destination retry", quiet = true)
            }
            READ -> readDestination("manual READ")
            CANCEL -> { cancelCard(); record("Pending and displayed recommendation canceled") }
            ROUTE -> route(intent)
            DEFER_PARKING -> {
                val store = VisitStore(this)
                ProbeState.activeVisit = store.load().active != null
                ProbeState.gearContext()
                if (store.error == null && ProbeState.parkingPrompt.defer(intent.getStringExtra("parkingToken"), intent.getStringExtra("lotId"),
                        intent.getLongExtra("requestedAt", -1), SystemClock.elapsedRealtime())) {
                    ProbeState.gearContext(); manager.cancel(PARKING_ID)
                    record("Explicit parking defer accepted; no save, reminder or Activity launch")
                } else record("Ignored stale, opened or expired parking defer")
            }
        }
        return START_NOT_STICKY
    }
    private fun startListener() {
        val status = Notification.Builder(this, STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_navigation).setContentTitle("ParkFlow · 주차 준비 중")
            .setContentText("내비 목적지와 주차 확인을 준비해요 · 설정에서 중지")
            .setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(STATUS_ID, status, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(STATUS_ID, status)
            enabled = true; ProbeState.running = true; ProbeState.failure = FlowFailure.NONE; ProbeState.destinationRead = DestinationReadStatus.IDLE; ProbeState.phase = ConnectionPhase.CONNECTING; ProbeState.flowError = null; ProbeState.destinationName = null; ProbeState.connection = "Connecting"
            startGearMonitor(); handler.post(parkingRefresh)
            record("Explicit user start; foreground listener active (specialUse)")
            val sdk = NaviHelper(applicationContext)
            helper = sdk
            sdk.addListener(listener)
            sdk.initialize(onConnected = { onMain {
                connected = true; ProbeState.phase = ConnectionPhase.READY; ProbeState.connection = "Connected (initial connection callback)"
                record("NaviHelper connected"); readDestination("initial connection")
            } }, onFailed = { error -> onMain {
                connected = false; ProbeState.selection = null; ProbeState.resetGear(); syncParkingNotification(); ProbeState.phase = ConnectionPhase.FAILED; ProbeState.flowError = "내비 연결에 실패했어요. 다시 연결해주세요."; ProbeState.connection = "Connection failed; stop/start to retry"
                record("NaviHelper connection failed: $error")
            } })
        } catch (error: Exception) {
            ProbeState.flowError = "내비 연결을 시작할 수 없어요. 다시 시도해주세요."; record("Listener startup failed: $error")
            stopSelf()
        }
    }
    private fun startGearMonitor() {
        ProbeState.activeVisit = VisitStore(this).load().active != null
        ProbeState.resetGear()
        // AAOS may expose Car classes through its boot classpath rather than pm shared libraries.
        val supported = packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        if (supported) try {
            gearMonitor = GearMonitor(this) { park -> if (enabled) { ProbeState.activeVisit = VisitStore(this).load().active != null; ProbeState.gearSample(park); syncParkingNotification() } }.also { it.start() }
        } catch (e: LinkageError) {
            ProbeState.gearStatus = "차량 기어 라이브러리 사용 불가 · 수동 기록 가능"
            record("Public AAOS gear API linkage unavailable: $e")
        } catch (e: Exception) {
            ProbeState.gearStatus = "차량 기어 연결 불가 · 수동 기록 가능"
            record("Public AAOS gear monitor unavailable: $e")
        }
        else ProbeState.gearStatus = "차량 기어 API 미지원 · 수동 기록 가능"
    }
    private fun onMain(block: () -> Unit) { handler.post { if (enabled) block() } }
    private fun readDestination(reason: String, quiet: Boolean = false) {
        if (!connected) {
            ProbeState.failure = FlowFailure.DESTINATION_READ
            ProbeState.flowError = "내비 연결을 확인한 뒤 다시 시도해주세요."
            record("Cannot read destination: SDK not connected"); return
        }
        val token = destinationRead.begin(SystemClock.elapsedRealtime(), quiet)
        ProbeState.destinationRead = destinationRead.status; ProbeState.failure = FlowFailure.NONE; ProbeState.flowError = null
        try {
            requestedRead = reason to SystemClock.elapsedRealtime()
            helper?.getDestinationInfo()
            record("getDestinationInfo REQUESTED [$reason]; awaiting callback, no request ID")
            handler.postDelayed({ if (enabled && destinationRead.expire(token, SystemClock.elapsedRealtime())) {
                requestedRead = null; ProbeState.destinationRead = destinationRead.status
                ProbeState.failure = FlowFailure.DESTINATION_READ
                ProbeState.flowError = "목적지 확인이 늦어지고 있어요. 다시 확인해주세요."
                record("Destination read timed out; explicit retry available")
            } }, 8000)
        } catch (error: Exception) {
            requestedRead = null; destinationRead.failed(token); ProbeState.destinationRead = destinationRead.status
            ProbeState.failure = FlowFailure.DESTINATION_READ
            ProbeState.flowError = "목적지를 불러오지 못했어요. 다시 확인해주세요."
            record("getDestinationInfo failed: $error")
        }
    }
    private fun foregroundNavigation() {
        if (!connected) ProbeState.flowError = "내비 연결 후 다시 시도해주세요."
        else try { helper?.requestNavigationScreen(NavigationScreenRequest.Foreground); ProbeState.flowError = null }
        catch (e: Exception) { ProbeState.flowError = "내비 화면을 열 수 없어요. 차량 내비를 직접 열어주세요."; record("Foreground failed: $e") }
    }
    private fun syncParkingNotification() {
        ProbeState.activeVisit = VisitStore(this).load().active != null
        ProbeState.gearContext()
        val ticket = ProbeState.parkingPrompt.live(SystemClock.elapsedRealtime())
        if (ticket == null) { manager.cancel(PARKING_ID); return }
        if (postedParkingToken == ticket.token) return
        // Mark attempted even when disabled: no retry/repost loop without a new transition.
        postedParkingToken = ticket.token
        manager.cancel(PARKING_ID)
        if (!notificationsAllowed() || manager.getNotificationChannel(PARKING_CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) {
            record("Parking notification disabled; use app → 주차했어요 for manual confirmation"); return
        }
        val open = PendingIntent.getActivity(this, 20,
            Intent(this, MainActivity::class.java).setAction(OPEN_PARKING)
                .setData(Uri.parse("parkflow-probe://parking/${ticket.token}"))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("parkingToken", ticket.token).putExtra("lotId", ticket.lotId).putExtra("requestedAt", ticket.requestedAt),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
        val defer = PendingIntent.getService(this, 21,
            Intent(this, ProbeService::class.java).setAction(DEFER_PARKING)
                .setData(Uri.parse("parkflow-probe://parking-defer/${ticket.token}"))
                .putExtra("parkingToken", ticket.token).putExtra("lotId", ticket.lotId).putExtra("requestedAt", ticket.requestedAt),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
        // Pleos also removes the popup on action click. Generic system removal is
        // presentation state, not a user's domain dismissal: do not invalidate the ticket.
        // postedParkingToken prevents reposting; explicit decline/gear/context/TTL still invalidate.
        // NAVIGATION is a PoC classification, including the contest release;
        // production eligibility is unconfirmed.
        val category = if (BuildConfig.PARKING_NAV_POC) Notification.CATEGORY_NAVIGATION else Notification.CATEGORY_REMINDER
        val notification = Notification.Builder(this, PARKING_CHANNEL)
            .setSmallIcon(R.drawable.ic_navigation).setContentTitle("주차 기록 남기기")
            .setContentText("${ProbeState.selection?.lot?.name} · P 전환 감지, 주차 여부를 직접 확인해주세요")
            .setCategory(category).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open).setAutoCancel(true).setTimeoutAfter(120_000)
            .addAction(Notification.Action.Builder(null, "기록 남기기", open).build())
            .addAction(Notification.Action.Builder(null, "나중에", defer).build()).build()
        try { manager.notify(PARKING_ID, notification); record("Parking confirmation notify() returned; P is not arrival proof; user tap required") }
        catch (e: Exception) { record("Parking notification posting failed: $e") }
    }
    private fun notificationsAllowed() = manager.areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    private fun postCard(target: DemoTarget, candidate: ParkingCandidate? = null) {
        if (!target.valid()) { record("Invalid fixture target; notification skipped"); return }
        if (!notificationsAllowed()) { record("Notifications disabled or permission denied; no recommendation posted"); return }
        val channel = manager.getNotificationChannel(CARD_CHANNEL)
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) { record("Recommendation channel disabled; no recommendation posted"); return }
        if (channel.importance < NotificationManager.IMPORTANCE_HIGH) record("Recommendation channel importance is ${channel.importance}; heads-up unlikely. Check channel settings.")
        ProbeState.recommendationActions.invalidate()
        activeToken = UUID.randomUUID().toString(); cardTarget = target; cardLot = candidate?.lot
        session.issue(activeToken!!, SystemClock.elapsedRealtime(), candidate != null)
        val ticket = if (candidate != null) ProbeState.recommendationActions.issue(activeToken!!, ProbeState.snapshot, SystemClock.elapsedRealtime()) else null
        fun Intent.bindRecommendation(): Intent = apply {
            putExtra("token", activeToken)
            ticket?.let { putExtra("generation", it.generation); putExtra("destination", it.destination); putExtra("minutes", it.minutes) }
        }
        // Extras do not distinguish PendingIntents. Give each card a unique filter identity
        // so an already-issued action keeps its original token and fails the stale-token guard.
        val action = PendingIntent.getService(this, 10,
            Intent(this, ProbeService::class.java).setAction(ROUTE)
                .setData(Uri.parse("parkflow-probe://recommendation/$activeToken"))
                .bindRecommendation(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
        val fee = candidate?.estimatedWon?.let { "${it}원" } ?: "요금 미상"
        // Pleos HUN renders only title/contentText/action; essential disclosures must stay here.
        val title = if (candidate == null) "데모 주차 추천 · 가상 요금" else "${target.name} · ${minutes}분 추정 $fee"
        val summary = if (candidate == null) "${target.name} · 가상 1,000원/시간" else "직선 ${candidate.distanceMeters.toInt()}m · 강남구 ${candidate.lot.sourceDate} 자료\n운영·빈자리 미확인"
        val detail = if (candidate == null) "수동 DEMO: ${target.name}\n가상 요금 1,000원/시간 · 실제 주차정보 아님" else
            "$title\n$summary\n할인·일주차권 미반영, 입구 미검증\n서울 강남구 공공데이터 · KOGL 제1유형"
        val comparison = ticket?.let {
            PendingIntent.getActivity(this, 11,
                Intent(this, MainActivity::class.java).setAction(OPEN_COMPARISON)
                    .setData(Uri.parse("parkflow-probe://comparison/${it.token}"))
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).bindRecommendation(),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
        }
        val builder = Notification.Builder(this, CARD_CHANNEL)
            .setSmallIcon(R.drawable.ic_navigation)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true).setTimeoutAfter(120_000)
            .addAction(Notification.Action.Builder(null, if (candidate == null) "데모 목적지로 안내" else "이곳으로 안내", action).build())
        comparison?.let { builder.addAction(Notification.Action.Builder(null, "다른 주차장 보기", it).build()) }
        val card = builder.build()
        try {
            manager.notify(CARD_ID, card)
            record("${if (candidate == null) "DEMO" else "PUBLIC AUTO"} Recommendation notify() returned: ${target.name} (${target.latitude}, ${target.longitude}). Posted is NOT proof of heads-up visibility.")
        } catch (error: Exception) { cancelCard(); record("Posting failed: $error") }
    }
    private fun route(intent: Intent) {
        val token = intent.getStringExtra("token")
        val target = cardTarget
        if (token == null || token != activeToken || target == null || !session.consume(token, SystemClock.elapsedRealtime())) { record("Ignored stale / missing recommendation action"); return }
        if (cardLot != null) {
            val store = VisitStore(this)
            val ledger = store.load()
            if (store.error != null || !ProbeState.recommendationActions.consume(token, intent.getLongExtra("generation", -1),
                    intent.getStringExtra("destination"), intent.getIntExtra("minutes", -1), ProbeState.snapshot,
                    getSharedPreferences("probe", MODE_PRIVATE).getInt("minutes", 60), ProbeState.running,
                    ledger.active != null, ProbeState.selection != null, false, SystemClock.elapsedRealtime())) {
                record("Ignored consumed / changed recommendation context"); return
            }
        }
        // Consume the one-shot recommendation; a disconnected SDK requires a new card/action.
        val lot = cardLot
        activeToken = null; cardTarget = null; cardLot = null; manager.cancel(CARD_ID)
        if (!connected) { record("Route action received by SERVICE; SDK not connected, no request sent"); return }
        try {
            if (lot != null) markRequested(lot)
            record("Route action received by SERVICE; requestRoute ${if (lot == null) "DEMO" else "PUBLIC parking"} ${target.name}; no activity launched by this action")
            helper?.requestRoute(RouteInfo(latitude = target.latitude, longitude = target.longitude,
                poiName = target.name, poiId = "", address = lot?.address.orEmpty(), poiSubId = ""))
            record("requestRoute returned; awaiting SDK onRoutingCompleted/onError. Return is NOT proof of route acceptance.")
        } catch (error: Exception) { session.clearSelection(); ProbeState.selection = null; ProbeState.gearContext(); ProbeState.flowError = "안내 요청에 실패했어요. 목적지를 다시 확인해주세요."; record("requestRoute failed: $error; pending parking selection cleared"); recommend(false) }
    }
    private fun markRequested(lot: ParkingLot) {
        ProbeState.selection = NavigationSelection(lot, NavigationSelectionState.REQUESTED, session.destination?.identity, SystemClock.elapsedRealtime())
        ProbeState.gearContext()
        session.selected(lot, SystemClock.elapsedRealtime())
        clearCandidates()
        handler.postDelayed({ if (enabled) {
            val previous = ProbeState.selection
            updateSelection()
            if (previous?.state == NavigationSelectionState.REQUESTED && ProbeState.selection == null) {
                ProbeState.flowError = "내비 응답 시간이 지났어요. 목적지를 확인하고 다시 선택해주세요."
                session.clearSelection(); recommend(false)
            }
        } }, 30000)
    }
    private fun requestParking(lot: ParkingLot) {
        if (!connected) { record("SDK not connected; no parking request"); return }
        try {
            markRequested(lot)
            helper?.requestRoute(RouteInfo(latitude = lot.latitude, longitude = lot.longitude,
                poiName = lot.displayName, poiId = "", address = lot.address, poiSubId = ""))
            record("Explicit comparison choice requestRoute returned; destination readback required for acceptance: ${lot.id}")
        } catch (e: Exception) { session.clearSelection(); ProbeState.selection = null; ProbeState.gearContext(); ProbeState.flowError = "안내 요청에 실패했어요. 다시 시도해주세요."; record("Parking request failed: $e"); recommend(false) }
    }
    private fun loadParking() {
        // 50-row local snapshot, loaded once at explicit service startup; no network or recurring work.
        try {
            val lots = loadParkingLots(this)
            engine = RecommendationEngine(lots)
            record("Historical public snapshot loaded: ${lots.size} rows; Seoul Gangnam-gu / KOGL Type1 / 2025-09-05")
        } catch (e: Exception) {
            engine = null; ProbeState.result = ComparisonResult.UNAVAILABLE; ProbeState.recommendation = "공공 주차자료 사용 불가"
            record("Parking asset unavailable/malformed; no candidates: $e")
        }
    }
    private fun recommend(notify: Boolean = true) {
        if (!session.canRecommend(SystemClock.elapsedRealtime())) {
            ProbeState.recommendation = if (!autoEnabled) "자동 추천 꺼짐" else "선택한 주차장 · 재추천 생략"
            record(ProbeState.recommendation); return
        }
        val d = session.destination ?: return
        val source = engine ?: run { ProbeState.result = ComparisonResult.UNAVAILABLE; ProbeState.recommendation = "공공 주차자료 사용 불가"; record(ProbeState.recommendation); return }
        val candidates = source.nearby(d.latitude, d.longitude, minutes)
        ProbeState.result = if (candidates.isEmpty()) ComparisonResult.EMPTY else ComparisonResult.FOUND
        ProbeState.snapshot = CandidateSnapshot(++generation, d.identity, minutes, candidates.toList())
        ProbeState.candidates = candidates.joinToString("\n") {
            "${it.lot.name} · 직선 ${it.distanceMeters.toInt()}m · ${minutes}분 추정 ${it.estimatedWon?.let { won -> "${won}원" } ?: "미상"} · ${it.lot.sourceDate}"
        }
        ProbeState.recommendation = if (candidates.isEmpty()) "1km 내 자료 없음 · 알림 없음" else "가까운 주차장 ${candidates.size}곳 · 과거 자료"
        record("Automatic recommendation: ${ProbeState.recommendation}; ${ProbeState.candidates}")
        if (notify) candidates.firstOrNull()?.let { postCard(DemoTarget(it.lot.latitude, it.lot.longitude, it.lot.displayName), it) }
    }
    private fun cancelPending() { pending?.let { handler.removeCallbacks(it) }; pending = null }
    private fun cancelCard() { ProbeState.recommendationActions.invalidate(); cancelPending(); activeToken = null; cardTarget = null; cardLot = null; session.invalidate(); manager.cancel(CARD_ID) }
    private fun record(message: String) = ProbeState.log(this, message)
    override fun onDestroy() {
        enabled = false; connected = false; gearMonitor?.stop(); gearMonitor = null; ProbeState.resetGear(); manager.cancel(PARKING_ID); cancelCard(); handler.removeCallbacksAndMessages(null)
        try { helper?.removeListener(listener); helper?.release() } catch (error: Exception) { record("SDK release error: $error") }
        helper = null; ProbeState.selection = null; ProbeState.gearContext(); clearCandidates(); ProbeState.running = false; ProbeState.phase = ConnectionPhase.STOPPED; ProbeState.destinationName = null; ProbeState.connection = "Stopped"
        stopForeground(STOP_FOREGROUND_REMOVE); record("Listener stopped; SDK released")
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val OPEN_COMPARISON = "io.parkflow.probe.OPEN_COMPARISON"
        const val DEFER_PARKING = "io.parkflow.probe.DEFER_PARKING"
        const val OPEN_PARKING = "io.parkflow.probe.OPEN_PARKING"
        const val PARKING_CHANNEL = "parking_confirmation_v1"
        private const val PARKING_ID = 3
        const val RETRY = "io.parkflow.probe.RETRY"
        const val FOREGROUND = "io.parkflow.probe.FOREGROUND"
        const val SELECT = "io.parkflow.probe.SELECT"
        const val DURATION = "io.parkflow.probe.DURATION"
        const val AUTO = "io.parkflow.probe.AUTO"
        const val START = "io.parkflow.probe.START"
        const val POST = "io.parkflow.probe.POST"
        const val DELAY = "io.parkflow.probe.DELAY"
        const val READ = "io.parkflow.probe.READ"
        const val CANCEL = "io.parkflow.probe.CANCEL"
        const val ROUTE = "io.parkflow.probe.ROUTE"
        const val CARD_CHANNEL = "demo_navigation_v1"
        private const val STATUS_CHANNEL = "listener_status_v1"
        private const val STATUS_ID = 1
        const val CARD_ID = 2
    }
}
