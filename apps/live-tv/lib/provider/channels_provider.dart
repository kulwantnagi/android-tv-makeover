import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';

import '../model/channel.dart';

/// Sidebar order. Categories with no channels are hidden.
const categoryOrder = [
  'News',
  'Entertainment',
  'Movies',
  'Music',
  'Sports',
  'Kids',
  'Infotainment',
  'Business',
  'Devotional',
  'Other',
];

/// Loads every playlist in the catalog, keeps 1080p Hindi and Punjabi streams only, adds logos and
/// categories from the iptv-org India index, and caches the result on disk.
class ChannelsProvider {
  static const streamsCatalogUrl =
      'https://raw.githubusercontent.com/kananinirav/Indian-IPTV-App/refs/heads/master/data/streams.csv';

  /// Carries logos and group-titles (categories) for Indian channels.
  static const logoIndexUrl = 'https://iptv-org.github.io/iptv/countries/in.m3u';

  /// Only channels in these languages are kept (ISO 639-3 codes; iptv-org publishes a playlist per
  /// language listing its channels): Hindi and Punjabi.
  static const languages = ['hin', 'pan'];
  static String languageUrl(String code) => 'https://iptv-org.github.io/iptv/languages/$code.m3u';

  static const _cacheFile = 'channels_v3.json';   // v3: language filter (older caches had every language)
  static const _timeout = Duration(seconds: 20);

  /// Last result saved on disk, so the app can show channels instantly on start.
  Future<List<Channel>?> loadCached() async {
    try {
      final f = File('${(await getApplicationSupportDirectory()).path}/$_cacheFile');
      if (!await f.exists()) return null;
      final list = await compute(_decodeChannels, await f.readAsString());
      return list.isEmpty ? null : list;
    } catch (_) {
      return null;
    }
  }

  /// Downloads everything in parallel, parses off the UI thread, saves the cache.
  Future<List<Channel>> refresh() async {
    final catalog = await http.get(Uri.parse(streamsCatalogUrl)).timeout(_timeout);
    if (catalog.statusCode != 200) throw Exception('Failed to load stream sources');
    final sources = _parseCatalog(catalog.body);

    final responses = await Future.wait([
      http.get(Uri.parse(logoIndexUrl)).timeout(_timeout).then((r) => r.statusCode == 200 ? r.body : '',
          onError: (_) => ''),
      for (final l in languages)
        http.get(Uri.parse(languageUrl(l))).timeout(_timeout).then((r) => r.statusCode == 200 ? r.body : '',
            onError: (_) => ''),
      for (final s in sources)
        http.get(Uri.parse(s.value)).timeout(_timeout).then((r) => r.statusCode == 200 ? r.body : '',
            onError: (_) => ''),
    ]);

    final langBodies = responses.sublist(1, 1 + languages.length);
    // without the language lists we can't filter: keep the last good (cached) list instead
    if (langBodies.every((b) => b.isEmpty)) throw Exception('Failed to load channel languages');
    final first = 1 + languages.length;
    final args = <String, dynamic>{
      'index': responses.first,
      'languages': langBodies,
      'playlists': [
        for (var i = 0; i < sources.length; i++) {'name': sources[i].key, 'body': responses[first + i]},
      ],
    };
    final json = await compute(_buildChannels, args);
    if (json.isEmpty) throw Exception('No channels found');

    try {
      final f = File('${(await getApplicationSupportDirectory()).path}/$_cacheFile');
      await f.writeAsString(jsonEncode(json));
    } catch (_) {}
    return json.map(Channel.fromJson).toList();
  }

  static List<MapEntry<String, String>> _parseCatalog(String csv) {
    final out = <MapEntry<String, String>>[];
    for (final line in csv.split('\n')) {
      final t = line.trim();
      if (t.isEmpty || t.toLowerCase().startsWith('name,')) continue;
      final comma = t.indexOf(',');
      if (comma <= 0) continue;
      out.add(MapEntry(t.substring(0, comma).trim(), t.substring(comma + 1).trim()));
    }
    return out;
  }
}

List<Channel> _decodeChannels(String text) {
  final list = jsonDecode(text) as List;
  return list.map((e) => Channel.fromJson(e as Map<String, dynamic>)).toList();
}

// ---------------------------------------------------------------- parsing (runs in an isolate)

final _tvgId = RegExp(r'tvg-id="([^"]*)"');
final _tvgLogo = RegExp(r'tvg-logo="([^"]*)"');
final _group = RegExp(r'group-title="([^"]*)"');

class _Entry {
  final String info;
  final String url;
  _Entry(this.info, this.url);

  String attr(RegExp r) => r.firstMatch(info)?.group(1) ?? '';
  String get name => info.substring(info.lastIndexOf(',') + 1).trim();
}

List<_Entry> _entries(String body) {
  final out = <_Entry>[];
  String? info;
  for (final raw in body.split('\n')) {
    final line = raw.trim();
    if (line.startsWith('#EXTINF:')) {
      info = line;
    } else if (line.isNotEmpty && !line.startsWith('#') && info != null) {
      out.add(_Entry(info, line));
      info = null;
    }
  }
  return out;
}

/// "AajTak.in@HD" -> "aajtak.in": logos and categories are shared by every feed.
String _baseId(String id) => id.split('@').first.toLowerCase();

/// "Aaj Tak HD (1080p) [Geo-blocked]" -> "aajtak"
String _nameKey(String name) => name
    .toLowerCase()
    .replaceAll(RegExp(r'\(.*?\)|\[.*?\]'), '')
    .replaceAll(RegExp(r'\bhd\b'), '')
    .replaceAll(RegExp(r'[^a-z0-9]'), '');

/// "Aaj Tak HD (1080p) [Geo-blocked]" -> "Aaj Tak HD"
String _cleanName(String name) =>
    name.replaceAll(RegExp(r'\s*\(\d+[pi]\)'), '').replaceAll(RegExp(r'\s*\[.*?\]'), '').trim();

String _category(String group) {
  switch (group.split(';').first.trim().toLowerCase()) {
    case 'news':
    case 'legislative':
      return 'News';
    case 'entertainment':
    case 'general':
    case 'comedy':
    case 'series':
      return 'Entertainment';
    case 'movies':
    case 'classic':
      return 'Movies';
    case 'music':
      return 'Music';
    case 'sports':
      return 'Sports';
    case 'kids':
    case 'animation':
    case 'family':
      return 'Kids';
    case 'documentary':
    case 'science':
    case 'travel':
    case 'culture':
    case 'education':
    case 'lifestyle':
    case 'cooking':
    case 'outdoor':
      return 'Infotainment';
    case 'business':
      return 'Business';
    case 'religious':
      return 'Devotional';
    default:
      return 'Other';
  }
}

List<Map<String, dynamic>> _buildChannels(Map<String, dynamic> args) {
  // index: channel id / name -> logo + group
  final logoById = <String, String>{}, logoByName = <String, String>{};
  final groupById = <String, String>{}, groupByName = <String, String>{};
  for (final e in _entries(args['index'] as String)) {
    final id = _baseId(e.attr(_tvgId));
    final key = _nameKey(e.name);
    final logo = e.attr(_tvgLogo);
    final group = e.attr(_group);
    if (logo.startsWith('http')) {
      if (id.isNotEmpty) logoById.putIfAbsent(id, () => logo);
      logoByName.putIfAbsent(key, () => logo);
    }
    if (group.isNotEmpty) {
      if (id.isNotEmpty) groupById.putIfAbsent(id, () => group);
      groupByName.putIfAbsent(key, () => group);
    }
  }

  // channels in the wanted languages, matched by channel id, then by name
  final langIds = <String>{}, langNames = <String>{};
  for (final body in (args['languages'] as List).cast<String>()) {
    for (final e in _entries(body)) {
      final id = _baseId(e.attr(_tvgId));
      if (id.isNotEmpty) langIds.add(id);
      langNames.add(_nameKey(e.name));
    }
  }

  // merge all playlists; same channel in the same category -> one tile with backup streams
  final byKey = <String, Map<String, dynamic>>{};
  for (final p in (args['playlists'] as List).cast<Map>()) {
    final sportsList = (p['name'] as String).toLowerCase().contains('sport');
    for (final e in _entries(p['body'] as String)) {
      if (!e.name.contains('(1080p)')) continue;   // 1080p only
      final id = e.attr(_tvgId);
      final base = _baseId(id);
      final key = _nameKey(e.name);
      if (!langIds.contains(base) && !langNames.contains(key)) continue;   // Hindi / Punjabi only
      final category = sportsList
          ? 'Sports'
          : _category(e.attr(_group).isNotEmpty ? e.attr(_group) : (groupById[base] ?? groupByName[key] ?? ''));
      var logo = e.attr(_tvgLogo);
      if (!logo.startsWith('http')) logo = logoById[base] ?? logoByName[key] ?? '';
      final existing = byKey['$category|$key'];
      if (existing != null) {
        final urls = existing['s'] as List<String>;
        if (!urls.contains(e.url)) urls.add(e.url);
        continue;
      }
      byKey['$category|$key'] = {
        'n': _cleanName(e.name),
        'l': logo,
        'c': category,
        'i': id,
        's': <String>[e.url],
      };
    }
  }

  final list = byKey.values.toList();
  bool indian(Map m) => (m['i'] as String).contains('.in@') || (m['i'] as String).endsWith('.in');
  list.sort((a, b) {
    // Indian channels first (matters for the mostly international Sports list), then by name
    final ia = indian(a) ? 0 : 1, ib = indian(b) ? 0 : 1;
    if (ia != ib) return ia - ib;
    return (a['n'] as String).toLowerCase().compareTo((b['n'] as String).toLowerCase());
  });
  return list;
}
