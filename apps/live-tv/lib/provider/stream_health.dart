import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';

import '../model/channel.dart';

/// Which streams work, so channels open on a working stream and dead channels are hidden.
///
/// Two sources, saved on disk:
///  - what the player saw on this TV (`played` / `failed`): the ground truth;
///  - a background check that downloads each stream's playlist (`ok` / `dead`): catches servers
///    that are down before you ever open the channel.
/// Public stream lists change daily, so every verdict expires and is checked again.
class StreamHealth {
  StreamHealth._();
  static final instance = StreamHealth._();

  static const _file = 'stream_health.json';
  static const _fresh = Duration(hours: 12);     // re-check after this
  static const _badFor = Duration(hours: 24);    // a dead/failed stream gets another chance after this
  static const _timeout = Duration(seconds: 8);
  static const _agent = 'Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36';

  /// url -> {s: played|ok|dead|failed, t: ms since epoch}
  final Map<String, Map<String, dynamic>> _state = {};
  bool _loaded = false;
  bool _checking = false;
  Timer? _saveTimer;

  Future<void> load() async {
    if (_loaded) return;
    _loaded = true;
    try {
      final f = File('${(await getApplicationSupportDirectory()).path}/$_file');
      if (await f.exists()) {
        (jsonDecode(await f.readAsString()) as Map).forEach((k, v) => _state[k as String] = Map<String, dynamic>.from(v as Map));
      }
    } catch (_) {}
  }

  void _set(String url, String s) {
    _state[url] = {'s': s, 't': DateTime.now().millisecondsSinceEpoch};
    _saveTimer?.cancel();
    _saveTimer = Timer(const Duration(seconds: 2), _save);
  }

  Future<void> _save() async {
    try {
      final f = File('${(await getApplicationSupportDirectory()).path}/$_file');
      await f.writeAsString(jsonEncode(_state));
    } catch (_) {}
  }

  Duration _age(Map<String, dynamic> e) =>
      Duration(milliseconds: DateTime.now().millisecondsSinceEpoch - (e['t'] as int));

  /// 0 played here, 1 playlist ok, 2 unknown, 3 unusable (dead or failed, until it expires)
  int _rank(String url) {
    final e = _state[url];
    if (e == null) return 2;
    final s = e['s'] as String;
    if (s == 'dead' || s == 'failed') return _age(e) < _badFor ? 3 : 2;
    return s == 'played' ? 0 : 1;
  }

  /// The player started this stream.
  void markPlayed(String url) => _set(url, 'played');

  /// The player could not start this stream.
  void markFailed(String url) => _set(url, 'failed');

  /// Channels with their best stream first; channels with no usable stream are left out.
  List<Channel> apply(List<Channel> channels) {
    final out = <Channel>[];
    for (final c in channels) {
      final urls = [...c.streamUrls];
      final order = {for (var i = 0; i < urls.length; i++) urls[i]: i};
      urls.sort((a, b) {
        final r = _rank(a) - _rank(b);
        return r != 0 ? r : order[a]! - order[b]!;
      });
      if (_rank(urls.first) == 3) continue;   // even the best one is unusable
      out.add(Channel(name: c.name, logoUrl: c.logoUrl, category: c.category, tvgId: c.tvgId, streamUrls: urls));
    }
    return out;
  }

  /// Checks every stream without a fresh verdict, a few at a time. Returns true if anything changed.
  Future<bool> check(List<Channel> channels) async {
    if (_checking) return false;
    _checking = true;
    try {
      final todo = <String>[];
      for (final c in channels) {
        for (final u in c.streamUrls) {
          final e = _state[u];
          if (e == null || _age(e) > _fresh) todo.add(u);
        }
      }
      if (todo.isEmpty) return false;
      var changed = false;
      final client = http.Client();
      var next = 0;
      Future<void> worker() async {
        while (next < todo.length) {
          final u = todo[next++];
          // one retry: a slow answer is not a dead server
          final ok = await _alive(client, u) || await _alive(client, u);
          final before = _rank(u);
          // the player's own verdict is stronger than a playlist download: only refresh its time
          final prev = _state[u]?['s'];
          if (ok && (prev == 'played' || prev == 'failed')) {
            _set(u, prev as String == 'failed' && _age(_state[u]!) > _badFor ? 'ok' : prev);
          } else {
            _set(u, ok ? 'ok' : 'dead');
          }
          if (_rank(u) != before) changed = true;
        }
      }
      await Future.wait([for (var i = 0; i < 6; i++) worker()]);
      client.close();
      await _save();
      return changed;
    } finally {
      _checking = false;
    }
  }

  /// True if the playlist (and, for a master playlist, its first variant) can be downloaded.
  Future<bool> _alive(http.Client client, String url) async {
    try {
      final first = await _head(client, Uri.parse(url));
      if (first == null) return false;
      final body = first.$2;
      if (!body.trimLeft().startsWith('#EXTM3U')) {
        // not HLS (DASH, a direct stream, ...): it answered, let the player judge
        return true;
      }
      if (body.contains('#EXT-X-STREAM-INF')) {
        final lines = body.split('\n').map((l) => l.trim()).toList();
        final i = lines.indexWhere((l) => l.startsWith('#EXT-X-STREAM-INF'));
        final variant = lines.skip(i + 1).firstWhere((l) => l.isNotEmpty && !l.startsWith('#'), orElse: () => '');
        if (variant.isEmpty) return false;
        final second = await _head(client, first.$1.resolve(variant));
        return second != null && second.$2.contains('#EXTINF');
      }
      return body.contains('#EXTINF');
    } catch (_) {
      return false;
    }
  }

  /// GET that reads at most 64 KB (a direct stream never ends). Returns (final URL, text) on HTTP 200.
  Future<(Uri, String)?> _head(http.Client client, Uri uri) async {
    final req = http.Request('GET', uri)..headers['User-Agent'] = _agent;
    final res = await client.send(req).timeout(_timeout);
    if (res.statusCode != 200) {
      res.stream.listen(null).cancel();
      return null;
    }
    final bytes = <int>[];
    final done = Completer<void>();
    late StreamSubscription<List<int>> sub;
    sub = res.stream.listen((chunk) {
      bytes.addAll(chunk);
      if (bytes.length >= 64 * 1024 && !done.isCompleted) {
        sub.cancel();
        done.complete();
      }
    }, onDone: () {
      if (!done.isCompleted) done.complete();
    }, onError: (_) {
      if (!done.isCompleted) done.complete();
    }, cancelOnError: true);
    await done.future.timeout(_timeout, onTimeout: () => sub.cancel());
    return (res.request?.url ?? uri, utf8.decode(bytes, allowMalformed: true));
  }
}
