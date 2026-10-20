package com.app.nuri.call.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.app.nuri.call.CallMedia
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * One WebRTC peer connection for a follow-through call (design
 * §17.1). Non-trickle ICE: the SDP handed to signaling is produced
 * only after candidate gathering completes, so each side posts
 * exactly one signal blob. Media is encrypted by WebRTC itself
 * (DTLS-SRTP is mandatory in the spec); nothing is recorded —
 * tracks end with the call.
 *
 * Deliberately small surface: createOffer / acceptOffer /
 * acceptAnswer / setVideoEnabled / close. The coordinator owns
 * all policy; this class owns only the peer connection.
 */
class WebRtcEngine(context: Context) {

    interface Listener {
        fun onConnected()
        fun onConnectFailed()
        fun onDisconnected()
    }

    @Volatile
    var listener: Listener? = null

    private val appContext = context.applicationContext
    private val eglBase: EglBase = EglBase.create()
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var localView: SurfaceViewRenderer? = null
    private var remoteView: SurfaceViewRenderer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var closed = false

    private fun ensureFactory() {
        if (factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun iceServers(): List<PeerConnection.IceServer> =
        listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())

    private inner class PcObserver : PeerConnection.Observer {
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                gatheringLatch?.countDown()
            }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED,
                -> mainHandler.post { listener?.onConnected() }
                PeerConnection.IceConnectionState.FAILED ->
                    mainHandler.post { listener?.onConnectFailed() }
                PeerConnection.IceConnectionState.DISCONNECTED ->
                    mainHandler.post { listener?.onDisconnected() }
                else -> {}
            }
        }

        override fun onAddStream(stream: MediaStream) {
            stream.videoTracks.firstOrNull()?.let { track ->
                remoteVideoTrack = track
                mainHandler.post { remoteView?.let { track.addSink(it) } }
            }
        }

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            (receiver.track() as? VideoTrack)?.let { track ->
                remoteVideoTrack = track
                mainHandler.post { remoteView?.let { track.addSink(it) } }
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceCandidate(candidate: IceCandidate) { /* non-trickle: candidates ride the SDP */ }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
    }

    @Volatile
    private var gatheringLatch: CountDownLatch? = null

    private fun newPeer(media: CallMedia): PeerConnection? {
        ensureFactory()
        val pc = factory?.createPeerConnection(iceServers(), PcObserver()) ?: return null
        peer = pc
        // Local media
        val audioSource: AudioSource? = factory?.createAudioSource(MediaConstraints())
        localAudioTrack = factory?.createAudioTrack("ilona-audio", audioSource)
        localAudioTrack?.let { pc.addTrack(it, listOf("ilona-stream")) }
        if (media == CallMedia.VIDEO_AND_AUDIO) {
            startLocalVideo(pc)
        }
        return pc
    }

    private fun startLocalVideo(pc: PeerConnection) {
        val enumerator = Camera2Enumerator(appContext)
        val device = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull() ?: return
        videoCapturer = enumerator.createCapturer(device, null)
        val helper = SurfaceTextureHelper.create("ilona-capture", eglBase.eglBaseContext)
        videoSource = factory?.createVideoSource(videoCapturer!!.isScreencast)
        videoCapturer?.initialize(helper, appContext, videoSource?.capturerObserver)
        videoCapturer?.startCapture(640, 480, 24)
        localVideoTrack = factory?.createVideoTrack("ilona-video", videoSource)
        localVideoTrack?.let { track ->
            pc.addTrack(track, listOf("ilona-stream"))
            mainHandler.post { localView?.let { track.addSink(it) } }
        }
    }

    private fun sdpObserver(
        onSet: () -> Unit = {},
        onCreate: (SessionDescription) -> Unit = {},
    ): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) = onCreate(desc)
        override fun onSetSuccess() = onSet()
        override fun onCreateFailure(error: String) {}
        override fun onSetFailure(error: String) {}
    }

    /** Produce a complete (post-gathering) SDP offer. Blocks the calling (IO) thread. */
    fun createOffer(media: CallMedia): String? {
        val pc = newPeer(media) ?: return null
        gatheringLatch = CountDownLatch(1)
        val createLatch = CountDownLatch(1)
        var offer: SessionDescription? = null
        pc.createOffer(
            sdpObserver(onCreate = { desc ->
                offer = desc
                pc.setLocalDescription(sdpObserver(), desc)
                createLatch.countDown()
            }),
            MediaConstraints(),
        )
        if (!createLatch.await(10, TimeUnit.SECONDS)) return null
        gatheringLatch?.await(6, TimeUnit.SECONDS)
        return pc.localDescription?.description ?: offer?.description
    }

    /** Accept a remote offer; produce the complete answer SDP. */
    fun acceptOffer(remoteSdp: String, media: CallMedia): String? {
        val pc = newPeer(media) ?: return null
        gatheringLatch = CountDownLatch(1)
        val setLatch = CountDownLatch(1)
        pc.setRemoteDescription(
            sdpObserver(onSet = { setLatch.countDown() }),
            SessionDescription(SessionDescription.Type.OFFER, remoteSdp),
        )
        if (!setLatch.await(10, TimeUnit.SECONDS)) return null
        val answerLatch = CountDownLatch(1)
        var answer: SessionDescription? = null
        pc.createAnswer(
            sdpObserver(onCreate = { desc ->
                answer = desc
                pc.setLocalDescription(sdpObserver(), desc)
                answerLatch.countDown()
            }),
            MediaConstraints(),
        )
        if (!answerLatch.await(10, TimeUnit.SECONDS)) return null
        gatheringLatch?.await(6, TimeUnit.SECONDS)
        return pc.localDescription?.description ?: answer?.description
    }

    fun acceptAnswer(remoteSdp: String) {
        val latch = CountDownLatch(1)
        peer?.setRemoteDescription(
            sdpObserver(onSet = { latch.countDown() }),
            SessionDescription(SessionDescription.Type.ANSWER, remoteSdp),
        )
        latch.await(10, TimeUnit.SECONDS)
    }

    fun attachViews(local: SurfaceViewRenderer?, remote: SurfaceViewRenderer?) {
        mainHandler.post {
            localView = local
            remoteView = remote
            local?.init(eglBase.eglBaseContext, null)
            remote?.init(eglBase.eglBaseContext, null)
            localVideoTrack?.let { t -> if (local != null) t.addSink(local) }
            remoteVideoTrack?.let { t -> if (remote != null) t.addSink(remote) }
        }
    }

    /** The in-call camera kill (design §17.5): video track off; the call continues voice-only. */
    fun setVideoEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
        try {
            if (enabled) {
                videoCapturer?.startCapture(640, 480, 24)
            } else {
                videoCapturer?.stopCapture()
            }
        } catch (_: Exception) {
        }
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            videoCapturer?.stopCapture()
        } catch (_: Exception) {
        }
        videoCapturer?.dispose()
        localVideoTrack?.dispose()
        localAudioTrack?.dispose()
        videoSource?.dispose()
        peer?.close()
        peer?.dispose()
        factory?.dispose()
        localView = null
        remoteView = null
    }
}
