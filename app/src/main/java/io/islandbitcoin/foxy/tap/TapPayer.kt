package io.islandbitcoin.foxy.tap

import io.islandbitcoin.foxy.debug.Log
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * The paying phone, which listens (Foxy/Bluetooth/TapLink.swift `TapPayer` at
 * 8df0bb1), over a GATT client ([TapCentral]): the scan and what counts as a
 * Foxy ([TapScanRules]), the proximity verdict ([TapProximity]), the quiet link
 * opened before the verdict (warming), the near doors that put CONNECT TO PAY on
 * the receiver's screen and the byte that comes back on them, the receiver's
 * model and which edges to hold together ([TapEdges]), a nearer receiver taking
 * the quiet link, TAP TO PAY pressed ([connectNearby]), and phones left alone a
 * while (not a Foxy; just paid). Once the verdict picks a receiver, everything
 * from the handshake to the change is [TapPayerLink]'s, made new for that link.
 *
 * The phone's Bluetooth is followed as iOS's central manager is: the system's
 * question asked when it never was, `denied` with no dialog once refused, `off`
 * while the radio is off, `unsupported` with no adapter or no scanner, and the
 * scan back on when the radio is. The listen is never ended by any of these:
 * the page's `tapPayStart` stays open (FoxyBridge+Tap.swift `handleTapPayStart`).
 *
 * Nothing scans while Foxy is put away ([foreground]); a link that has the offer
 * stays up, because the payment goes over it.
 *
 * Android differs in one place, on purpose: the scan is not stopped and started
 * again for each receiver given up on, as iOS's `retry` → `scan` does. Android
 * lets an app start a scan only five times in thirty seconds and then silently
 * starts none ("scanning too frequently"), so a few receivers given up on in a
 * row would leave a payer deaf. The scan runs from [start] to the offer (or
 * [stop], or Foxy put away), and what iOS's fresh scan does — the readings and
 * receivers forgotten — is done here without one.
 *
 * Main thread only, as iOS's main queue; times are the [TapBluetooth.clock]'s.
 */
class TapPayer(
    private val bluetooth: TapBluetooth,
    private val onStage: (TapStage) -> Unit,
    /** The offer, once it is in: the payload and the four digits. */
    private val done: (String, String) -> Unit,
    private val onChange: (String, (Boolean) -> Unit) -> Unit = { _, keep -> keep(false) },
    private val onTerms: (String) -> Unit = {},
    /** This phone's model, for [TapEdges]: `Build.MODEL` on a phone. */
    private val thisModel: String = "",
    private val newSession: (UUID) -> TapSession = { TapSession(TapSession.Role.PAYER, it) },
    private val diary: (String) -> Unit = Log::line,
    val proximity: TapProximity = TapProximity(),
) {
    companion object {
        /** How often the verdict is taken (iOS's `tick`). */
        const val TICK = 0.1

        /** A quiet link that has not opened in this long is asked for afresh (`warmOpenWait`). */
        const val WARM_OPEN_WAIT = 10.0

        /** An open quiet link silent about its strength this long turns warming off for the listen. */
        const val WARM_SILENT = 2.0

        /** A quiet link that came to nothing in this long, with no receiver told, is let go. */
        const val WARM_TOO_LONG = 20.0

        /** The quiet link moves to a nearer receiver at most this often. */
        const val MOVE_EVERY = 3.0

        /** A phone that is not a Foxy is left alone this long. */
        const val NOT_FOXY_SKIP = 10.0

        /** A receiver that answered a payment is left alone this long... */
        const val ANSWERED_SKIP = 90.0

        /** ...and nothing at all is looked at for this long. */
        const val QUIET_AFTER_ANSWER = 5.0

        /** TAP TO PAY pressed takes a receiver from this far for this long. */
        const val PRESSED_FOR = 5.0
        const val PRESSED_BELOW_NEARBY = 8

        /** The cards wait this long for the receiver's model, and for the near door's word. */
        const val MODEL_WAIT = 1.5
        const val DOOR_WAIT = 1.0
        const val NO_LINK_WAIT = 4.0

        /** `nearby` is said again this often while it lasts, and `far` once this long after it stopped. */
        const val NEARBY_EVERY = 1.0
        const val FAR_AFTER = 2.5

        /** iOS's Device Information service and its model number, which every iPhone serves to any connection. */
        val DEVICE_INFORMATION: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        val MODEL_NUMBER: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")

        /** iOS's `maximumWriteValueLength(.withResponse)`: one attribute value; a longer write than the MTU is a long write. */
        const val WRITE_LENGTH = TapGatt.MOST_VALUE

        /** The receivers' models, by address, for as long as Foxy runs (TapLink.swift `models`). */
        private val models = HashMap<String, String>()

        /** Forgets every model (the tests). */
        fun forgetModels() = models.clear()

        /** The model number as it is kept: letters, digits and commas, at most 24 (TapLink.swift). */
        fun cleanModel(raw: ByteArray?): String? {
            val text = raw?.toString(Charsets.UTF_8) ?: return null
            return text.filter { it.isLetterOrDigit() || it == ',' }.take(24)
        }
    }

    private val clock = bluetooth.clock
    private var central: TapCentral? = null
    private var unwatch: (() -> Unit)? = null
    private var started = false
    private var finished = false
    private var front = true
    private var scanning = false
    private var radioUp = false

    private fun say(what: String) = diary("[tap] pay: $what")

    // what the scan heard: each receiver's service, and who is left alone
    private val seen = HashMap<String, UUID>()
    private val skip = HashMap<String, Double>()
    private var quietUntil = Double.NEGATIVE_INFINITY

    // the receiver the quiet link (and then the real one) is open to
    private var target: String? = null
    private var service: UUID? = null
    private var doors: Map<UUID, Set<UUID>> = emptyMap()
    private var link: TapPayerLink? = null
    private var tick: TapClock.Timer? = null

    private var warmReady = false
    private var nearNow = false
    private var asking = false
    private var warmSince = 0.0
    private var warmReadings = 0
    private var warmOpen = 0.0
    private var slowSaid = false
    private var slowTold = 0
    private var warmOff = false
    private var movedAt = Double.NEGATIVE_INFINITY
    private var lastLog = Double.NEGATIVE_INFINITY
    private var lastLogDbm = 0
    private var lastLogWhy = ""
    private var linked = false
    private var passedBy = 0
    private var notFoxy = 0
    private var lastNearby = 0.0

    // the near doors
    private var announced: String? = null
    private var nearSince = 0.0
    private var theirTurned = false
    private var theirEarly = false
    private var heardDoor = false
    private var announcedAt = 0.0

    private val theirModel: String get() = target?.let { models[it] } ?: ""
    private val edges: TapEdges.Edges get() = TapEdges.edges(thisModel, theirModel)

    /** The edge this phone's own CONNECT TO PAY card puts its mark on. */
    val edge: String
        get() {
            val e = edges.payer
            return if (theirTurned) (if (e == "left") "right" else "left") else e
        }

    /** How far the payment in hand has got, as a percentage, for the page. */
    val progress: Int get() = link?.progress ?: 0

    /** Whether the offer is in and the link held (the tests, and [TapBridge]). */
    val holding: Boolean get() = linked

    // MARK: the radio

    /** The listen begins: the radio's state, the system's question if it was never asked, then the scan. */
    fun start() {
        if (started || finished) return
        started = true
        val b = bluetooth
        if (!BluetoothAnswer.worthAsking(b.adapter, b.power(), b.scans) || b.scanPermitted()) return follow()
        if (b.scanRefused()) {
            say("the Bluetooth permissions were refused before; not asking again")
            return onStage(TapStage.DENIED)
        }
        say("asking for the Bluetooth permissions")
        b.askScan { granted ->
            if (finished) return@askScan
            if (!granted) {
                say("the Bluetooth permissions were refused")
                return@askScan onStage(TapStage.DENIED)
            }
            follow()
        }
    }

    private fun follow() {
        if (finished) return
        unwatch = bluetooth.watch { state(it) }
        state(bluetooth.power())
    }

    private fun state(power: TapBluetooth.Power) {
        if (finished) return
        val b = bluetooth
        val bad = BluetoothAnswer.stage(b.adapter, b.scanPermitted(), power, power == TapBluetooth.Power.ON && b.scans)
        if (bad != null) {
            if (radioUp) radioDown()
            return onStage(bad)
        }
        if (!radioUp) {
            val c = b.central(this)
            if (c == null) {
                say("Android would not give this phone a scanner")
                return onStage(TapStage.UNSUPPORTED)
            }
            central = c
            radioUp = true
        }
        scan()
    }

    /** The radio went: every link with it. A payment in flight is told so now. */
    private fun radioDown() {
        radioUp = false
        scanning = false
        tick?.cancel()
        tick = null
        val l = link
        link = null
        val c = central
        central = null
        target = null
        c?.close()
        if (l != null) {
            l.disconnected("Bluetooth went off")
            // a held link is spent with the radio: nothing more can go over it
            if (linked) l.stop()
        }
        resetTarget()
    }

    /** iOS's `scan`: everything about the last receiver forgotten, listening, and the verdict taken ten times a second. */
    private fun scan() {
        val c = central ?: return
        if (finished || linked || !front) return
        proximity.forget()
        seen.clear()
        resetTarget()
        if (!scanning) {
            scanning = c.startScan()
            if (!scanning) say("Android would not start a scan")
        }
        tick?.cancel()
        tick = everyTick()
    }

    private fun everyTick(): TapClock.Timer = clock.after(TICK) {
        tick = null
        if (finished || linked || !front) return@after
        judge()
        if (tick == null && !finished && !linked && !nearNow && front) tick = everyTick()
    }

    private fun resetTarget() {
        target = null
        service = null
        doors = emptyMap()
        announced = null
        theirTurned = false
        theirEarly = false
        heardDoor = false
        nearSince = 0.0
        warmReady = false
        nearNow = false
        asking = false
        warmSince = 0.0
        warmReadings = 0
        warmOpen = 0.0
        slowSaid = false
    }

    /** Ends the listen. A word still going out on the link goes first ([TapPayerLink.stop]). */
    fun stop() {
        if (finished) return
        finished = true
        tick?.cancel()
        tick = null
        unwatch?.invoke()
        unwatch = null
        if (passedBy > 0 || notFoxy > 0) say("the filter passed by $passedBy advertisement(s) and dropped $notFoxy that were not Foxy")
        val c = central
        if (scanning) c?.stopScan()
        scanning = false
        val l = link
        if (l != null) {
            // the link closes the radio once its last word is out (LinkRadio.close)
            l.stop()
        } else {
            target?.let { c?.disconnect(it) }
            c?.close()
            central = null
        }
    }

    /**
     * Foxy is in front (true) or put away. Away, nothing scans: the scan and the
     * verdict stop and a quiet link, or one still being made, is let go. A link
     * holding the offer is kept: the payment goes over it, and [askAgain] asks
     * what was missed once Foxy is back (TapLink.swift `askAgain`, FoxyWebView.swift `cameToFront`).
     */
    fun foreground(on: Boolean) {
        if (front == on) return
        front = on
        if (finished || linked) return
        if (!on) {
            tick?.cancel()
            tick = null
            if (scanning) {
                central?.stopScan()
                scanning = false
            }
            val l = link
            if (l != null) {
                l.fail("Foxy was put away")
            } else if (target != null) {
                target?.let { central?.disconnect(it) }
                resetTarget()
            }
            say("Foxy was put away; not listening")
            return
        }
        if (radioUp) {
            say("Foxy is back; listening again")
            scan()
        }
    }

    // MARK: what the scan hears

    /** One advertisement (CBCentralManagerDelegate `didDiscover`). */
    fun discovered(ad: TapAdvert) {
        if (finished || !front) return
        val now = clock.now()
        if (quietUntil > now) return
        skip[ad.device]?.let { if (it > now) return }
        val svc = TapScanRules.service(ad)
        if (svc == null) {
            passedBy++
            return
        }
        seen[ad.device] = svc
        proximity.add(ad.device, ad.rssi, now)
    }

    /**
     * TAP TO PAY was pressed: the receiver the toast is for is taken, without the
     * phones touching, for a few seconds (eight below the toast's own line). A
     * second press inside those seconds does nothing more, as on iOS.
     */
    fun connectNearby() {
        val touching = proximity.nearDbm
        val pressed = proximity.nearbyDbm - PRESSED_BELOW_NEARBY
        if (touching <= pressed || linked || finished) return
        say("TAP TO PAY pressed; taking a receiver from $pressed dBm")
        proximity.nearDbm = pressed
        clock.after(PRESSED_FOR) { proximity.nearDbm = touching }
        judge()
    }

    // MARK: the verdict

    private fun judge() {
        if (finished || nearNow || !front) return
        val c = central ?: return
        val now = clock.now()
        target?.let { warm ->
            // the readings for this one now come over the link, once it is really open
            if (!asking && c.isConnected(warm)) {
                asking = true
                c.readRssi(warm)
            }
            // open, and silent about its strength for two seconds: deciding on advertisements instead
            if (warmOpen > 0 && warmReadings == 0 && now - warmOpen > WARM_SILENT) {
                warmOff = true
                say("the open link will not say how strong it is; deciding on advertisements instead")
                return retry("the link would not answer")
            }
            // not open yet: waited for, said once, and asked afresh after WARM_OPEN_WAIT
            if (warmOpen == 0.0 && warmSince > 0 && now - warmSince > WARM_SILENT) {
                if (!slowSaid) {
                    slowSaid = true
                    slowTold++
                    if (slowTold <= 3) say("the link to that phone has not opened in 2 s; waiting for it")
                }
                if (now - warmSince > WARM_OPEN_WAIT) {
                    if (slowTold <= 3) say("the link to that phone has not opened in ${WARM_OPEN_WAIT.toInt()} s; asking again")
                    return retry("the link did not open")
                }
            }
            // not while the receiver has been told this phone is near: that "near" lives on this link
            if (warmSince > 0 && now - warmSince > WARM_TOO_LONG && announced == null) {
                say("a warm link came to nothing; letting it go")
                return retry("warm too long")
            }
        }
        // the quiet link follows the receiver that is nearest, at most once in three seconds
        val warm = target
        if (warm != null && now - movedAt > MOVE_EVERY) {
            val rival = proximity.rival(warm, now)
            val found = rival?.let { seen[it.id] }
            if (rival != null && found != null) {
                say("another receiver is nearer (${rival.median} dBm, against ${rival.theirs}); the quiet link moves to it")
                movedAt = now
                announce(false)
                c.disconnect(warm)
                resetTarget()
                openQuiet(rival.id, found, now)
                return
            }
        }
        when (val v = proximity.verdict(now)) {
            TapProximity.Verdict.Nothing -> warm(now)
            is TapProximity.Verdict.Far -> {
                far(v.dbm, now)
                warm(now)
            }
            is TapProximity.Verdict.Near -> near(v.id, v.dbm, now)
        }
    }

    private fun far(dbm: Int, now: Double) {
        // said when it changes: four decibels, a new reason, or ten seconds
        val why = proximity.why
        if (now - lastLog > 0.5 && (abs(dbm - lastLogDbm) >= 4 || why != lastLogWhy || now - lastLog > 10)) {
            lastLog = now
            lastLogDbm = dbm
            lastLogWhy = why
            say("something at $dbm dBm, not close enough ($why)")
        }
        if (dbm >= proximity.nearbyDbm) {
            if (nearSince == 0.0) nearSince = now
            // neither card until the other phone's model is read, for a second and a half at most
            val waited = now - nearSince > MODEL_WAIT
            if (theirModel.isNotEmpty() || waited) announce(true)
            // and the card here waits for the receiver's word on which screen it is on
            val ready = if (announced != null) heardDoor || now - announcedAt > DOOR_WAIT else now - nearSince > NO_LINK_WAIT
            if (ready && !theirEarly && now - lastNearby > NEARBY_EVERY) {
                lastNearby = now
                onStage(TapStage.NEARBY)
            }
        } else if (lastNearby > 0 && now - lastNearby > FAR_AFTER) {
            lastNearby = 0.0
            nearSince = 0.0
            onStage(TapStage.FAR)
            announce(false)
        } else if (lastNearby == 0.0) {
            nearSince = 0.0
        }
    }

    private fun near(id: String, dbm: Int, now: Double) {
        val found = seen[id] ?: return
        val c = central ?: return
        // the quiet link comes first, always (not when warm links are off)
        if (!warmOff && (target != id || !warmReady)) {
            if (target != id) {
                target?.let { c.disconnect(it) }
                resetTarget()
                openQuiet(id, found, now)
            }
            return
        }
        if (target == id && warmReady) {
            if (announced == null) announce(true)
            val unheard = announced != null && !heardDoor && now - announcedAt < DOOR_WAIT
            if (theirEarly || unheard) {
                if (now - lastLog > 1.0) {
                    lastLog = now
                    say(
                        "touching at $dbm dBm, but that phone " +
                            if (theirEarly) "is still on its amount screen; waiting for NEXT" else "has not said which screen it is on yet",
                    )
                }
                return
            }
        }
        say(
            "a receiver at $dbm dBm (needs ${proximity.lastThreshold}" +
                (if (proximity.decidedOnLink) ", over the link" else ", advertised") +
                "), ${if (target == id) "already linked" else "connecting"}",
        )
        nearNow = true
        tick?.cancel()
        tick = null
        val l = makeLink(id, found)
        l.connecting()
        if (target == id) {
            // the phone the quiet link already belongs to: one round trip to go
            if (warmReady) l.speak()
            return
        }
        // somebody else's link was warm: dropped, and this one opened the long way
        target?.let { c.disconnect(it) }
        warmReady = false
        warmOpen = 0.0
        target = id
        service = found
        c.connect(id)
    }

    /** Opens a link before the verdict, and says nothing down it. */
    private fun warm(now: Double) {
        if (warmOff || target != null) return
        val id = proximity.warmest(now) ?: return
        val found = seen[id] ?: return
        openQuiet(id, found, now)
    }

    private fun openQuiet(id: String, found: UUID, now: Double) {
        target = id
        service = found
        warmReady = false
        warmSince = now
        warmReadings = 0
        warmOpen = 0.0
        slowSaid = false
        central?.connect(id)
    }

    private fun makeLink(id: String, svc: UUID): TapPayerLink {
        val radio = LinkRadio(id, svc)
        val l = TapPayerLink(
            svc, radio, clock,
            onStage = onStage,
            done = done,
            onChange = onChange,
            onTerms = onTerms,
            newSession = newSession,
            diary = diary,
        )
        radio.link = l
        link = l
        return l
    }

    /**
     * iOS's `retry`, the part after the words: the target let go (and left alone
     * a while when it was not a Foxy), everything about it forgotten, and
     * listening again. The link, when there was one, has already said `gone` and
     * `searching` if anything had been said on screen.
     */
    private fun retry(why: String) {
        if (finished || linked) return
        val l = link
        if (l != null) {
            // the link's own give-up comes back here through LinkRadio.giveUp
            l.fail(why)
            return
        }
        letGo(why, wasNear = false)
    }

    private fun letGo(why: String, wasNear: Boolean) {
        link = null
        val t = target
        if (t != null) {
            // only a phone that is not a Foxy is left alone: one whose doors went has re-armed with a new service
            if (wasNear && (why.startsWith("not a Foxy") || why.startsWith("the doors are not Foxy"))) {
                skip[t] = clock.now() + NOT_FOXY_SKIP
            }
            central?.disconnect(t)
        }
        resetTarget()
        if (!finished && front) scan()
    }

    // MARK: the GATT client's answers

    /** didConnect. */
    fun connected(device: String) {
        if (finished || device != target) return
        if (service == null) return retry("no service")
        if (warmOpen == 0.0) {
            warmOpen = clock.now()
            if (slowSaid && warmSince > 0) say("the link to that phone opened after ${String.format(Locale.ROOT, "%.1f", warmOpen - warmSince)} s")
        }
        central?.discover(device)
    }

    /** didFailToConnect. */
    fun connectFailed(device: String) {
        if (finished || device != target) return
        retry("could not connect")
    }

    /** didDisconnectPeripheral: the receiver let the link go, or it was lost. */
    fun disconnected(device: String, error: String? = null) {
        if (device != target || (finished && link == null)) return
        val l = link
        if (l != null) {
            l.disconnected(error)
            return
        }
        if (!linked) retry("disconnected")
    }

    /**
     * The services the receiver serves (didDiscoverServices and didDiscoverCharacteristicsFor
     * together, as Android finds them at once): not a Foxy without the service; not one
     * without its in and out doors; its model read when it serves one.
     */
    fun servicesFound(device: String, services: Map<UUID, Set<UUID>>) {
        if (finished || device != target) return
        val s = service ?: return
        val svc = services[s]
        if (svc == null) {
            notFoxy++
            return retry("not a Foxy")
        }
        val layout = TapGatt.Layout(s)
        if (layout.out.uuid !in svc || layout.into.uuid !in svc) {
            // the derived doors are what say this is a Foxy, not the shape
            notFoxy++
            return retry("the doors are not Foxy's")
        }
        doors = services
        warmReady = true
        if (services[DEVICE_INFORMATION]?.contains(MODEL_NUMBER) == true) central?.read(device, DEVICE_INFORMATION, MODEL_NUMBER)
        // subscribing is what makes the receiver notice, and it waits for the verdict
        if (nearNow) link?.speak()
    }

    /**
     * The receiver's service went away (Android's `onServiceChanged`, iOS's
     * `didModifyServices`): it left its screen, so the card comes down.
     */
    fun servicesChanged(device: String) {
        if (finished || device != target || linked || nearNow) return
        say("the receiver went off the air; the card comes down")
        if (lastNearby > 0) {
            lastNearby = 0.0
            onStage(TapStage.FAR)
        }
        retry("the receiver went off the air")
    }

    /** didReadRSSI. */
    fun rssi(device: String, dbm: Int, ok: Boolean) {
        asking = false
        if (device != target || finished || nearNow || !ok) return
        if (warmReadings == 0) {
            val heard = proximity.lastHeard(device)
            say("the open link answers at $dbm dBm" + (heard?.let { " (its advertisement said $it)" } ?: ""))
        }
        warmReadings++
        proximity.addLinked(device, dbm, clock.now())
    }

    /** didUpdateNotificationStateFor. Only the out door's matters: it is the link's subscribe. */
    fun notifyState(device: String, door: UUID, error: String?) {
        if (device != target || (finished && link == null)) return
        val s = service ?: return
        if (door == TapCrypto.outCharacteristic(s)) link?.subscribed(error)
    }

    /** didUpdateValueFor, a notification: a piece of the receiver's message, or a near door's byte. */
    fun valueChanged(device: String, door: UUID, value: ByteArray) {
        // a stop waiting on its last word still hears the receiver, as iOS's does
        if (device != target || (finished && link == null)) return
        val s = service ?: return
        val layout = TapGatt.Layout(s)
        if (layout.edge(door) != null) return nearByte(value)
        if (door == layout.out.uuid) link?.notified(value)
    }

    private fun nearByte(value: ByteArray) {
        val byte = value.firstOrNull()?.toInt()?.and(0xff) ?: 0
        val upside = byte and 1 == 1
        val isEarly = byte and 2 == 2
        heardDoor = true
        say("the near door said $byte")
        if (isEarly != theirEarly) {
            theirEarly = isEarly
            say("that phone is ${if (isEarly) "still on its amount screen; no card yet" else "on its invoice screen"}")
            if (isEarly && lastNearby > 0) {
                lastNearby = 0.0
                onStage(TapStage.FAR)
            }
        }
        if (upside != theirTurned) {
            theirTurned = upside
            say("that phone is ${if (upside) "top-down" else "upright"}; this one's mark goes $edge")
            if (lastNearby > 0) onStage(TapStage.NEARBY)
        }
    }

    /** didUpdateValueFor, a read: the receiver's model number. */
    fun valueRead(device: String, door: UUID, value: ByteArray?) {
        if (finished || device != target || door != MODEL_NUMBER) return
        val model = cleanModel(value) ?: return
        if (model == theirModel) return
        models[device] = model
        say("that phone is a $model; this one's ${edges.payer} edge to its ${edges.receiver}")
        // a card already up, on either screen, is put on the right edge
        if (announced != null) announce(true)
        if (lastNearby > 0) onStage(TapStage.NEARBY)
    }

    /** didWriteValueFor. */
    fun wrote(device: String, door: UUID, error: String?) {
        if (device != target) return
        val s = service ?: return
        if (door != TapCrypto.inCharacteristic(s)) return
        link?.wrote(error)
    }

    // MARK: the near doors

    /** "Near" or "not near" to the receiver, on a near door only: CONNECT TO PAY on their screen, on the edge chosen. */
    private fun announce(on: Boolean) {
        val want: String? = if (on) edges.receiver else null
        val s = service ?: return
        val t = target ?: return
        val c = central ?: return
        if (want == announced || !warmReady || !c.isConnected(t)) return
        val svc = doors[s] ?: return
        val layout = TapGatt.Layout(s)
        // the new door first, so the receiver never sees nobody near in between
        if (want != null && layout.nearDoor(want) in svc) c.setNotify(t, s, layout.nearDoor(want), true)
        val was = announced
        if (was != null && layout.nearDoor(was) in svc) c.setNotify(t, s, layout.nearDoor(was), false)
        announced = want
        if (want != null) announcedAt = clock.now()
    }

    // MARK: the page's words over the held link

    /** M10: this phone's price, a question, or the word that an invoice is paid. */
    fun sendQuote(text: String): Boolean = link?.sendQuote(text) ?: false

    /** Hands the payment over the held link; [then] is told once what became of it. */
    fun send(body: String, then: (TapPayerLink.Handover) -> Unit) {
        val l = link
        if (l == null) {
            then(TapPayerLink.Handover.Lost("That phone is no longer connected."))
            return
        }
        l.send(body, then)
    }

    /** Foxy is back in front with a payment handed over: the receiver is asked to say it again (M12). */
    fun askAgain() {
        link?.askAgain()
    }

    /** The GATT client's side of one [TapPayerLink]: the receiver it was made for, and nothing else. */
    private inner class LinkRadio(private val device: String, private val svc: UUID) : TapPayerLink.Radio {
        lateinit var link: TapPayerLink
        private val mine: Boolean get() = this@TapPayer.link === link

        override fun subscribe() {
            if (!mine) return
            central?.setNotify(device, svc, TapCrypto.outCharacteristic(svc), true)
        }

        override fun write(piece: ByteArray) {
            val c = central
            if (!mine || c == null) {
                // the radio went with the link: the write is answered, not left waiting
                clock.after(0.0) { link.wrote("the radio is off") }
                return
            }
            c.write(device, svc, TapCrypto.inCharacteristic(svc), piece)
        }

        override fun writeLength() = WRITE_LENGTH

        override val connected: Boolean get() = mine && central?.isConnected(device) == true

        override fun giveUp(why: String) {
            if (!mine) return
            letGo(why, wasNear = true)
        }

        override fun holdLink() {
            if (!mine) return
            // the handshake is over and the link is held: this phone has the receiver it came for
            linked = true
            tick?.cancel()
            tick = null
            if (scanning) {
                central?.stopScan()
                scanning = false
            }
        }

        override fun answered() {
            // that phone is left alone a while, and nothing is looked at for a few seconds
            val now = clock.now()
            skip[device] = now + ANSWERED_SKIP
            quietUntil = now + QUIET_AFTER_ANSWER
        }

        override fun close() {
            val c = central
            c?.disconnect(device)
            if (finished) {
                c?.close()
                central = null
            }
        }
    }
}
