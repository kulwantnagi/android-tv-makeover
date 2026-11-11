import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:video_player/video_player.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

import '../model/channel.dart';
import '../provider/stream_health.dart';
import '../widgets/channel_icon.dart';
import 'home.dart' show isSelectKey;

/// Full-screen live player.
/// Up/Down (or Channel +/-) changes channel, OK shows the info banner, Back returns.
/// A stream that fails or stalls on start falls back to the channel's backup streams.
class Player extends StatefulWidget {
  final List<Channel> channels;
  final int index;

  const Player({super.key, required this.channels, required this.index});

  @override
  State<Player> createState() => _PlayerState();
}

class _PlayerState extends State<Player> {
  static const _startTimeout = Duration(seconds: 12);

  late int index = widget.index;
  int streamIdx = 0;
  VideoPlayerController? controller;
  bool failed = false;
  bool showInfo = true;
  Timer? infoTimer, startTimer, zapTimer;
  int _generation = 0;
  final focus = FocusNode();

  Channel get channel => widget.channels[index];

  @override
  void initState() {
    super.initState();
    WakelockPlus.enable();
    _open();
  }

  @override
  void dispose() {
    infoTimer?.cancel();
    startTimer?.cancel();
    zapTimer?.cancel();
    controller?.dispose();
    focus.dispose();
    WakelockPlus.disable();
    super.dispose();
  }

  Future<void> _open() async {
    final gen = ++_generation;
    final old = controller;
    controller = null;
    failed = false;
    showInfo = true;          // banner stays up until the video actually starts
    infoTimer?.cancel();
    setState(() {});
    old?.dispose();

    final url = channel.streamUrls[streamIdx];
    final c = VideoPlayerController.networkUrl(Uri.parse(url));
    controller = c;
    startTimer?.cancel();
    startTimer = Timer(_startTimeout, () {
      if (gen == _generation && !c.value.isInitialized) _nextStream(gen);
    });
    c.addListener(() {
      if (gen == _generation && c.value.hasError) _nextStream(gen);
    });
    try {
      await c.initialize();
      if (gen != _generation) return;
      startTimer?.cancel();
      StreamHealth.instance.markPlayed(url);   // next time this channel opens on this stream
      await c.play();
      _checkAudio(c, gen);
      if (mounted) setState(_flashInfo);
    } catch (_) {
      if (gen == _generation) _nextStream(gen);
    }
  }

  /// Makes sure the stream has sound this TV can play.
  ///
  /// Some channels carry a second, surround (Dolby) audio track; the TV's speakers can't play it
  /// and the player may pick it, giving silence: switch to the plain AAC / stereo track. And when
  /// a stream has audio but the TV has no decoder for any of it (e.g. MP2), nothing is selected:
  /// that counts as a failed stream, so a backup is tried and the stream is remembered as bad.
  Future<void> _checkAudio(VideoPlayerController c, int gen) async {
    try {
      if (!c.isAudioTrackSupportAvailable()) return;
      bool plain(VideoAudioTrack t) {
        final codec = (t.codec ?? '').toLowerCase();
        final surround = codec.contains('ac-3') || codec.contains('ac3') || codec.contains('ec-3') ||
            codec.contains('eac3') || codec.contains('dts') || (t.channelCount ?? 2) > 2;
        return !surround;
      }
      // tracks settle a moment after playback starts
      await Future<void>.delayed(const Duration(seconds: 3));
      if (gen != _generation || !mounted) return;
      var tracks = await c.getAudioTracks();
      if (tracks.isEmpty) return;   // a channel without any audio: nothing to choose
      final current = tracks.where((t) => t.isSelected);
      if (current.isNotEmpty && plain(current.first)) return;
      final better = tracks.where(plain);
      if (better.isNotEmpty) {
        await c.selectAudioTrack(better.first.id);
        await Future<void>.delayed(const Duration(seconds: 2));
        if (gen != _generation || !mounted) return;
        tracks = await c.getAudioTracks();
      }
      if (!tracks.any((t) => t.isSelected)) _nextStream(gen);   // no audio this TV can decode
    } catch (_) {}
  }

  /// Current stream failed: try the channel's next backup, else show the error.
  void _nextStream(int gen) {
    if (gen != _generation || !mounted) return;
    StreamHealth.instance.markFailed(channel.streamUrls[streamIdx]);
    if (streamIdx + 1 < channel.streamUrls.length) {
      streamIdx++;
      _open();
    } else {
      startTimer?.cancel();
      setState(() {
        failed = true;
        showInfo = true;
      });
      infoTimer?.cancel();
    }
  }

  void _zap(int delta) {
    final n = widget.channels.length;
    setState(() {
      index = (index + delta) % n;
      if (index < 0) index += n;
      streamIdx = 0;
      showInfo = true;
    });
    infoTimer?.cancel();
    // pressing Up/Down quickly skips channels without starting each stream
    zapTimer?.cancel();
    zapTimer = Timer(const Duration(milliseconds: 350), _open);
  }

  void _flashInfo() {
    showInfo = true;
    infoTimer?.cancel();
    infoTimer = Timer(const Duration(seconds: 4), () {
      if (mounted && !failed) setState(() => showInfo = false);
    });
  }

  KeyEventResult _onKey(FocusNode _, KeyEvent e) {
    if (e is KeyUpEvent) return KeyEventResult.ignored;
    final k = e.logicalKey;
    if (k == LogicalKeyboardKey.arrowUp || k == LogicalKeyboardKey.channelUp) {
      _zap(-1);
    } else if (k == LogicalKeyboardKey.arrowDown || k == LogicalKeyboardKey.channelDown) {
      _zap(1);
    } else if (isSelectKey(k) || k == LogicalKeyboardKey.arrowLeft || k == LogicalKeyboardKey.arrowRight) {
      if (e is KeyDownEvent) {
        if (failed) {
          streamIdx = 0;
          _open();
        } else {
          setState(_flashInfo);
        }
      }
    } else {
      return KeyEventResult.ignored;
    }
    return KeyEventResult.handled;
  }

  @override
  Widget build(BuildContext context) {
    final c = controller;
    final ready = c != null && c.value.isInitialized;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) Navigator.of(context).pop(index);
      },
      child: Focus(
        focusNode: focus,
        autofocus: true,
        onKeyEvent: _onKey,
        child: Scaffold(
          backgroundColor: Colors.black,
          body: Stack(
            fit: StackFit.expand,
            children: [
              if (ready)
                Center(
                  child: AspectRatio(
                    aspectRatio: c.value.aspectRatio > 0 ? c.value.aspectRatio : 16 / 9,
                    child: VideoPlayer(c),
                  ),
                ),
              if (!failed)
                // only the spinner listens to playback updates, not the whole screen
                ValueListenableBuilder<VideoPlayerValue>(
                  valueListenable: c ?? ValueNotifier(const VideoPlayerValue.uninitialized()),
                  builder: (_, v, __) => (!v.isInitialized || v.isBuffering)
                      ? const Center(
                          child: SizedBox(
                              width: 34,
                              height: 34,
                              child: CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white70)))
                      : const SizedBox.shrink(),
                ),
              if (failed)
                Center(
                  child: Column(mainAxisSize: MainAxisSize.min, children: [
                    const Icon(Icons.signal_wifi_bad_rounded, color: Colors.white54, size: 40),
                    const SizedBox(height: 12),
                    const Text('This channel isn’t available right now',
                        style: TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.w600)),
                    const SizedBox(height: 6),
                    Text('OK to retry  ·  ▲ ▼ for another channel',
                        style: TextStyle(color: Colors.white.withValues(alpha: 0.6), fontSize: 13)),
                  ]),
                ),
              AnimatedOpacity(
                opacity: showInfo ? 1 : 0,
                duration: const Duration(milliseconds: 250),
                child: Align(alignment: Alignment.bottomCenter, child: _infoBar()),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _infoBar() {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(32, 40, 32, 26),
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Color(0x00000000), Color(0xCC000000)],
        ),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          ChannelIcon(key: ValueKey(channel.logoUrl + channel.name), channel: channel, width: 96, height: 56),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(channel.name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(color: Colors.white, fontSize: 22, fontWeight: FontWeight.w700)),
                const SizedBox(height: 4),
                Text(
                  '${channel.category}  ·  ${index + 1} of ${widget.channels.length}  ·  1080p'
                  '${streamIdx > 0 ? '  ·  backup stream ${streamIdx + 1}' : ''}',
                  style: TextStyle(color: Colors.white.withValues(alpha: 0.65), fontSize: 13),
                ),
              ],
            ),
          ),
          Text('▲ ▼  change channel', style: TextStyle(color: Colors.white.withValues(alpha: 0.5), fontSize: 12)),
        ],
      ),
    );
  }
}
