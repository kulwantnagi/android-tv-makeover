import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart' show ScrollCacheExtent;
import 'package:flutter/services.dart';

import '../model/channel.dart';
import '../provider/channels_provider.dart';
import '../provider/stream_health.dart';
import '../widgets/channel_icon.dart';
import 'player.dart';

const bg = Color(0xFF0B0B0F);
const sidebarBg = Color(0xFF131318);
const dim = Color(0xFF8E8E98);

const _icons = <String, IconData>{
  'All': Icons.apps_rounded,
  'News': Icons.newspaper_rounded,
  'Entertainment': Icons.theater_comedy_rounded,
  'Movies': Icons.movie_rounded,
  'Music': Icons.music_note_rounded,
  'Sports': Icons.sports_cricket_rounded,
  'Kids': Icons.child_care_rounded,
  'Infotainment': Icons.explore_rounded,
  'Business': Icons.trending_up_rounded,
  'Devotional': Icons.temple_hindu_rounded,
  'Other': Icons.more_horiz_rounded,
};

bool isSelectKey(LogicalKeyboardKey k) =>
    k == LogicalKeyboardKey.select ||
    k == LogicalKeyboardKey.enter ||
    k == LogicalKeyboardKey.numpadEnter ||
    k == LogicalKeyboardKey.gameButtonA;

class Home extends StatefulWidget {
  const Home({super.key});

  @override
  State<Home> createState() => _HomeState();
}

/// Category sidebar on the left, channel grid on the right. D-pad movement is handled
/// explicitly (not by Flutter's geometric focus search) so it is predictable.
class _HomeState extends State<Home> {
  static const cols = 6;

  final provider = ChannelsProvider();

  List<String> categories = [];
  Map<String, List<Channel>> byCategory = {};
  int selected = 0;
  bool loading = true;
  String? error;

  final List<FocusNode> sideNodes = [];
  /// one node per tile, keyed "category/index"; kept until the screen closes
  final Map<String, FocusNode> tileNodes = {};
  double rowExtent = 100;

  /// Each category keeps its own grid (built once, then kept alive offstage), scroll position and
  /// last focused tile, so returning to a category costs nothing.
  final Map<String, ScrollController> _controllers = {};
  final Map<String, int> _lastTile = {};
  final Map<String, Widget> _grids = {};
  final List<String> _mounted = [];
  Size? _gridSizes;

  /// Categories not visited yet are pre-built one at a time while the remote is idle.
  Timer? _prebuildTimer;
  DateTime _lastInput = DateTime.fromMillisecondsSinceEpoch(0);

  String get _cat => categories[selected];
  ScrollController get grid => _controllers.putIfAbsent(_cat, () => ScrollController());
  int get lastTile => _lastTile[_cat] ?? 0;
  set lastTile(int v) => _lastTile[_cat] = v;

  /// Moving through the sidebar only moves the highlight; the grid switches once you pause.
  Timer? _switchTimer;
  static const _switchDelay = Duration(milliseconds: 200);

  List<Channel> get current =>
      categories.isEmpty ? const [] : byCategory[categories[selected]] ?? const [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final health = StreamHealth.instance;
    await health.load();
    // 1) cached list -> instant start (best stream first, dead channels left out)
    final cached = await provider.loadCached();
    if (cached != null && mounted) _setChannels(health.apply(cached));
    // 2) fresh list in the background
    try {
      final fresh = await provider.refresh();
      if (mounted) _setChannels(health.apply(fresh));
      // 3) check the streams; apply the result once the remote has been idle for a while,
      //    so the grid doesn't change under your fingers
      if (await health.check(fresh)) {
        while (mounted && DateTime.now().difference(_lastInput) < const Duration(seconds: 8)) {
          await Future<void>.delayed(const Duration(seconds: 3));
        }
        if (mounted) _setChannels(health.apply(fresh));
      }
    } catch (e) {
      if (mounted && categories.isEmpty) {
        setState(() {
          loading = false;
          error = 'Could not load channels. Check the internet connection.';
        });
      }
    }
  }

  void _setChannels(List<Channel> channels) {
    final map = <String, List<Channel>>{'All': channels};
    for (final c in channels) {
      (map[c.category] ??= []).add(c);
    }
    final cats = ['All', ...categoryOrder.where((c) => map[c]?.isNotEmpty ?? false)];
    final first = categories.isEmpty;
    final keep = first ? 'All' : categories[selected];
    setState(() {
      byCategory = map;
      categories = cats;
      selected = cats.contains(keep) ? cats.indexOf(keep) : 0;
      loading = false;
      error = null;
      while (sideNodes.length < cats.length) {
        sideNodes.add(FocusNode());
      }
      // new data: rebuild grids lazily again
      _grids.clear();
      _mounted
        ..clear()
        ..add(cats[selected]);
    });
    _prebuildTimer?.cancel();
    _prebuildTimer = Timer.periodic(const Duration(milliseconds: 400), (_) => _prebuildNext());
    if (!first) {
      // the grid was rebuilt: put focus back on the current category
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && ModalRoute.of(context)?.isCurrent == true) sideNodes[selected].requestFocus();
      });
    }
    if (first) {
      WidgetsBinding.instance.addPostFrameCallback((_) => sideNodes[selected].requestFocus());
    }
  }

  @override
  void dispose() {
    for (final n in sideNodes) {
      n.dispose();
    }
    for (final n in tileNodes.values) {
      n.dispose();
    }
    _switchTimer?.cancel();
    _prebuildTimer?.cancel();
    for (final c in _controllers.values) {
      c.dispose();
    }
    super.dispose();
  }

  // ---------------------------------------------------------------- navigation

  FocusNode _tileNode(int i) => tileNodes.putIfAbsent('${categories[selected]}/$i', () => FocusNode());

  void _selectCategory(int i) {
    if (i == selected) return;
    setState(() {
      selected = i;
      if (!_mounted.contains(_cat)) _mounted.add(_cat);
    });
  }

  /// Builds one not-yet-visited category offstage, only when no key was pressed recently.
  void _prebuildNext() {
    if (!mounted || categories.isEmpty) return;
    if (DateTime.now().difference(_lastInput) < const Duration(milliseconds: 900)) return;
    final next = categories.where((c) => !_mounted.contains(c)).firstOrNull;
    if (next == null) {
      _prebuildTimer?.cancel();
      return;
    }
    setState(() => _mounted.add(next));
  }

  KeyEventResult _sideKey(int i, KeyEvent e) {
    if (e is KeyUpEvent) return KeyEventResult.ignored;
    _lastInput = DateTime.now();
    final k = e.logicalKey;
    if (k == LogicalKeyboardKey.arrowUp) {
      if (i > 0) sideNodes[i - 1].requestFocus();
      return KeyEventResult.handled;
    }
    if (k == LogicalKeyboardKey.arrowDown) {
      if (i < categories.length - 1) sideNodes[i + 1].requestFocus();
      return KeyEventResult.handled;
    }
    if (k == LogicalKeyboardKey.arrowRight || isSelectKey(k)) {
      if (i != selected) {
        _switchTimer?.cancel();
        _selectCategory(i);
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (current.isNotEmpty) _focusTile(lastTile.clamp(0, current.length - 1));
        });
      } else if (current.isNotEmpty) {
        _focusTile(lastTile.clamp(0, current.length - 1));
      }
      return KeyEventResult.handled;
    }
    if (k == LogicalKeyboardKey.arrowLeft) return KeyEventResult.handled;
    return KeyEventResult.ignored;
  }

  KeyEventResult _tileKey(int i, KeyEvent e) {
    if (e is KeyUpEvent) return KeyEventResult.ignored;
    _lastInput = DateTime.now();
    final k = e.logicalKey;
    final n = current.length;
    int? target;
    if (k == LogicalKeyboardKey.arrowLeft) {
      if (i % cols == 0) {
        sideNodes[selected].requestFocus();
        return KeyEventResult.handled;
      }
      target = i - 1;
    } else if (k == LogicalKeyboardKey.arrowRight) {
      if (i % cols < cols - 1 && i + 1 < n) target = i + 1;
    } else if (k == LogicalKeyboardKey.arrowUp) {
      if (i >= cols) target = i - cols;
    } else if (k == LogicalKeyboardKey.arrowDown) {
      if (i + cols < n) {
        target = i + cols;
      } else if (i ~/ cols < (n - 1) ~/ cols) {
        target = n - 1;
      }
    } else if (isSelectKey(k)) {
      if (e is KeyDownEvent) _play(i);
      return KeyEventResult.handled;
    } else if (k == LogicalKeyboardKey.goBack || k == LogicalKeyboardKey.escape) {
      // Back from the grid returns to the sidebar; Back on the sidebar exits
      sideNodes[selected].requestFocus();
      return KeyEventResult.handled;
    } else {
      return KeyEventResult.ignored;
    }
    if (target != null) _focusTile(target);
    return KeyEventResult.handled;
  }

  /// Scroll so the row is visible, then focus the tile (tiles are built lazily).
  void _focusTile(int i) {
    lastTile = i;
    if (grid.hasClients) {
      final pos = grid.position;
      final top = (i ~/ cols) * rowExtent;
      const margin = 20.0;
      double? to;
      if (top - margin < pos.pixels) {
        to = top - margin;
      } else if (top + rowExtent + margin > pos.pixels + pos.viewportDimension) {
        to = top + rowExtent + margin - pos.viewportDimension;
      }
      if (to != null) {
        grid.animateTo(to.clamp(0.0, pos.maxScrollExtent),
            duration: const Duration(milliseconds: 140), curve: Curves.easeOut);
      }
    }
    final node = _tileNode(i);
    if (node.context != null) {
      node.requestFocus();
    } else {
      WidgetsBinding.instance.addPostFrameCallback((_) => node.requestFocus());
    }
  }

  Future<void> _play(int i) async {
    final list = current;
    final result = await Navigator.of(context).push<int>(PageRouteBuilder(
      pageBuilder: (_, __, ___) => Player(channels: list, index: i),
      transitionDuration: const Duration(milliseconds: 150),
      reverseTransitionDuration: const Duration(milliseconds: 120),
      transitionsBuilder: (_, a, __, child) => FadeTransition(opacity: a, child: child),
    ));
    // land on the channel that was playing last
    if (mounted && list.isNotEmpty) _focusTile((result ?? i).clamp(0, list.length - 1));
  }

  // ---------------------------------------------------------------- UI

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: bg,
      body: Row(
        children: [
          _sidebar(),
          Expanded(child: _content()),
        ],
      ),
    );
  }

  Widget _sidebar() {
    return Container(
      width: 210,
      color: sidebarBg,
      padding: const EdgeInsets.fromLTRB(14, 26, 14, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(left: 8, bottom: 18),
            child: Row(children: [
              Container(
                padding: const EdgeInsets.all(6),
                decoration: BoxDecoration(color: const Color(0xFFE53935), borderRadius: BorderRadius.circular(8)),
                child: const Icon(Icons.live_tv_rounded, size: 16, color: Colors.white),
              ),
              const SizedBox(width: 10),
              const Text('Live TV', style: TextStyle(color: Colors.white, fontSize: 19, fontWeight: FontWeight.w700)),
            ]),
          ),
          Expanded(
            child: ListView.builder(
              padding: EdgeInsets.zero,
              itemCount: categories.length,
              itemBuilder: (context, i) => _SideItem(
                node: sideNodes[i],
                icon: _icons[categories[i]] ?? Icons.tv,
                label: categories[i],
                count: byCategory[categories[i]]?.length ?? 0,
                selected: i == selected,
                onFocus: () {
                  _switchTimer?.cancel();
                  _switchTimer = Timer(_switchDelay, () {
                    if (mounted) _selectCategory(i);
                  });
                },
                onKey: (e) => _sideKey(i, e),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _content() {
    if (loading && categories.isEmpty) {
      return const Center(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          SizedBox(width: 28, height: 28, child: CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white)),
          SizedBox(height: 14),
          Text('Loading channels…', style: TextStyle(color: dim, fontSize: 14)),
        ]),
      );
    }
    if (error != null && categories.isEmpty) {
      return Center(child: Text(error!, style: const TextStyle(color: dim, fontSize: 15)));
    }
    final list = current;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(28, 26, 28, 4),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Text(categories[selected],
                  style: const TextStyle(color: Colors.white, fontSize: 24, fontWeight: FontWeight.w700)),
              const SizedBox(width: 12),
              Padding(
                padding: const EdgeInsets.only(bottom: 3),
                child: Text('${list.length} channels', style: const TextStyle(color: dim, fontSize: 13)),
              ),
              const SizedBox(width: 8),
              Container(
                margin: const EdgeInsets.only(bottom: 3),
                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                decoration: BoxDecoration(border: Border.all(color: dim), borderRadius: BorderRadius.circular(4)),
                child: const Text('1080p', style: TextStyle(color: dim, fontSize: 10, fontWeight: FontWeight.w600)),
              ),
            ],
          ),
        ),
        Expanded(
          child: LayoutBuilder(builder: (context, box) {
            if (_gridSizes != box.biggest) {
              _gridSizes = box.biggest;
              _grids.clear();
            }
            return IndexedStack(
              index: _mounted.indexOf(_cat),
              children: [
                // same widget instance each time -> Flutter skips rebuilding hidden grids
                for (final c in _mounted) _grids.putIfAbsent(c, () => _grid(c, box.maxWidth)),
              ],
            );
          }),
        ),
      ],
    );
  }

  Widget _grid(String cat, double width) {
    const pad = 20.0;
    final cellW = (width - pad * 2) / cols;
    final cardW = cellW - 14;
    final cardH = cardW * 0.58;
    rowExtent = cardH + 40;
    final list = byCategory[cat] ?? const <Channel>[];
    return GridView.builder(
      key: ValueKey('grid/$cat'),
      controller: _controllers.putIfAbsent(cat, () => ScrollController()),
      scrollCacheExtent: ScrollCacheExtent.pixels(rowExtent),
      addAutomaticKeepAlives: false,
      // one layer for the whole grid instead of one per tile: cheaper to composite on a Mali-450
      addRepaintBoundaries: false,
      padding: const EdgeInsets.fromLTRB(pad, 14, pad, 30),
      gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: cols,
        mainAxisExtent: rowExtent,
      ),
      itemCount: list.length,
      itemBuilder: (context, i) => _Tile(
        // the channel is part of the key: when the list changes, a tile must not keep the
        // previous channel's logo
        key: ValueKey('$cat/$i/${list[i].name}'),
        channel: list[i],
        node: tileNodes.putIfAbsent('$cat/$i', () => FocusNode()),
        cardW: cardW,
        cardH: cardH,
        onKey: (e) => _tileKey(i, e),
      ),
    );
  }
}

class _SideItem extends StatefulWidget {
  final FocusNode node;
  final IconData icon;
  final String label;
  final int count;
  final bool selected;
  final VoidCallback onFocus;
  final KeyEventResult Function(KeyEvent) onKey;

  const _SideItem({
    required this.node,
    required this.icon,
    required this.label,
    required this.count,
    required this.selected,
    required this.onFocus,
    required this.onKey,
  });

  @override
  State<_SideItem> createState() => _SideItemState();
}

class _SideItemState extends State<_SideItem> {
  bool focused = false;

  @override
  Widget build(BuildContext context) {
    final fg = focused ? Colors.black : (widget.selected ? Colors.white : const Color(0xFFB4B4BE));
    return Focus(
      focusNode: widget.node,
      onFocusChange: (f) {
        setState(() => focused = f);
        if (f) widget.onFocus();
      },
      onKeyEvent: (_, e) => widget.onKey(e),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 120),
        height: 38,
        margin: const EdgeInsets.only(bottom: 3),
        padding: const EdgeInsets.symmetric(horizontal: 10),
        decoration: BoxDecoration(
          color: focused
              ? Colors.white
              : widget.selected
                  ? const Color(0x1FFFFFFF)
                  : Colors.transparent,
          borderRadius: BorderRadius.circular(10),
        ),
        child: Row(children: [
          Icon(widget.icon, size: 18, color: fg),
          const SizedBox(width: 12),
          Expanded(
            child: Text(widget.label,
                style: TextStyle(
                    color: fg,
                    fontSize: 14,
                    fontWeight: focused || widget.selected ? FontWeight.w600 : FontWeight.w500)),
          ),
          Text('${widget.count}', style: TextStyle(color: focused ? Colors.black54 : dim, fontSize: 12)),
        ]),
      ),
    );
  }
}

class _Tile extends StatefulWidget {
  final Channel channel;
  final FocusNode node;
  final double cardW, cardH;
  final KeyEventResult Function(KeyEvent) onKey;

  const _Tile({
    super.key,
    required this.channel,
    required this.node,
    required this.cardW,
    required this.cardH,
    required this.onKey,
  });

  @override
  State<_Tile> createState() => _TileState();
}

class _TileState extends State<_Tile> {
  bool focused = false;

  @override
  Widget build(BuildContext context) {
    // Kept deliberately flat: ~40 of these are built whenever the category changes.
    Widget card = Padding(
      padding: const EdgeInsets.all(4.5),
      child: ChannelIcon(channel: widget.channel, width: widget.cardW - 9, height: widget.cardH - 9),
    );
    if (focused) {
      card = Transform.scale(
        scale: 1.1,
        child: DecoratedBox(
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(12.5),
            border: Border.all(color: Colors.white, width: 2),
          ),
          child: card,
        ),
      );
    }
    return Focus(
      focusNode: widget.node,
      onFocusChange: (f) => setState(() => focused = f),
      onKeyEvent: (_, e) => widget.onKey(e),
      child: Column(
        children: [
          card,
          const SizedBox(height: 7),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4),
            child: Text(
              widget.channel.name,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              textAlign: TextAlign.center,
              style: focused ? _nameFocused : _name,
            ),
          ),
        ],
      ),
    );
  }

  static const _name = TextStyle(color: Color(0xFFA0A0AA), fontSize: 11.5, fontWeight: FontWeight.w500);
  static const _nameFocused = TextStyle(color: Colors.white, fontSize: 11.5, fontWeight: FontWeight.w600);
}
