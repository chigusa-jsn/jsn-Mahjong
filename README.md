# jsn-Mahjong

[Riichi Mahjong](https://github.com/caxapexac/MinecraftRiichiMahjong)（MIT, by caxapexac）の 0.4.1（Minecraft 1.21.1）をもとに、
雀魂のような 2D の対局画面を追加したフォークです。

- **M キー**で対局画面を開閉（席に座っていると対局開始時に自動で開きます）
- 手牌を画面下に大きく表示。クリックで打牌、右クリックでツモ切り
- 4人の河・鳴き・ドラ・点数を雀魂風に表示。直前の捨て牌を強調
- 鳴き/和了ボタン、和了結果と「次の局へ」ボタン

最新の jar は [Releases の「latest」](../../releases/tag/latest) にあります（push のたびに GitHub Actions で自動ビルド）。

---

## 元の README

# Riichi Mahjong

Riichi Mahjong adds a fully playable Riichi Mahjong table to Minecraft for both **NeoForge** and **Fabric**.

## Features

- Full Riichi Mahjong ruleset with all yaku and specific rules (even chankan)
- Rule presets (Mahjong Soul / Tenhou / WRC)
- 1..4 player modes - bots fill empty seats
- Mahjong altar: craft items by scoring playing mahjong (what?)
- Yaku generator: generate RF energy by playing mahjong (what?)
- Fake mahjong: another game which was called mahjong for some reason
- FOV normalizer: enforce FOV in the area
- Achievements

## Dependencies

- Minecraft 1.21.1
- NeoForge 21.1.215+ or Fabric (loader 0.19.2+, Fabric API 0.116.11+)
- Architectury API 13.0.8+
- On Fabric: Team Reborn Energy 4.1.0 (bundled)

Recipes are balanced for vanilla - no extra dependencies required.

## Credits

Btw Fabric also has mahjong! [MahjongCraft](https://www.curseforge.com/minecraft/mc-mods/mahjongcraft).
