import { world, system, ItemStack, ItemTypes } from "@minecraft/server";
import { ActionFormData, ModalFormData } from "@minecraft/server-ui";

const PREFIX = "§6[Обман и доверие]§r ";
const STORAGE = "los:state:v2";
const DEFAULT_ROUNDS = 9;
const CLASSES = ["COMMON", "IRON_GEAR", "DIAMOND_GEAR", "NETHERITE_GEAR", "POTIONS_FOOD", "RARE", "LEGENDARY"];
const POOLS = {
  COMMON: ["minecraft:bread","minecraft:apple","minecraft:torch","minecraft:stick","minecraft:oak_planks","minecraft:cobblestone","minecraft:coal","minecraft:iron_nugget","minecraft:arrow","minecraft:string","minecraft:leather","minecraft:bone","minecraft:rotten_flesh","minecraft:glass_bottle"],
  IRON_GEAR: ["minecraft:iron_sword","minecraft:iron_pickaxe","minecraft:iron_axe","minecraft:iron_shovel","minecraft:iron_helmet","minecraft:iron_chestplate","minecraft:iron_leggings","minecraft:iron_boots","minecraft:shield","minecraft:bow","minecraft:crossbow"],
  DIAMOND_GEAR: ["minecraft:diamond_sword","minecraft:diamond_pickaxe","minecraft:diamond_axe","minecraft:diamond_shovel","minecraft:diamond_helmet","minecraft:diamond_chestplate","minecraft:diamond_leggings","minecraft:diamond_boots"],
  NETHERITE_GEAR: ["minecraft:netherite_sword","minecraft:netherite_pickaxe","minecraft:netherite_axe","minecraft:netherite_shovel","minecraft:netherite_helmet","minecraft:netherite_chestplate","minecraft:netherite_leggings","minecraft:netherite_boots"],
  POTIONS_FOOD: ["minecraft:potion","minecraft:milk_bucket","minecraft:golden_apple","minecraft:enchanted_golden_apple","minecraft:cooked_beef","minecraft:golden_carrot","minecraft:experience_bottle","minecraft:healing_potion"],
  RARE: ["minecraft:totem_of_undying","minecraft:elytra","minecraft:trident","minecraft:nether_star","minecraft:heart_of_the_sea","minecraft:conduit","minecraft:shulker_shell","minecraft:echo_shard"],
  LEGENDARY: ["minecraft:netherite_ingot","minecraft:enchanted_golden_apple","minecraft:dragon_breath","minecraft:beacon","minecraft:heavy_core","minecraft:music_disc_otherside"]
};
const MOB_TYPES = ["minecraft:ravager","minecraft:warden","minecraft:witch","minecraft:evoker","minecraft:piglin_brute","minecraft:blaze","minecraft:wither_skeleton","minecraft:guardian","minecraft:hoglin","minecraft:phantom"];
const state = loadState();
let match;
let queue = [];
let invites = new Map();
let lobbies = new Map();
let chestData;
let chestCooldown = 0;

function loadState() {
  const fallback = { stats: {}, settings: { itemOverrides: {}, rounds: DEFAULT_ROUNDS, arenaTeleport: false, restoreHealth: true, restoreHunger: true, chaos: false, enabledClasses: CLASSES.slice(), weights: { COMMON: 50, IRON_GEAR: 20, DIAMOND_GEAR: 12, NETHERITE_GEAR: 4, POTIONS_FOOD: 7, RARE: 5, LEGENDARY: 2 } }, arena: { first: null, second: null }, achievements: {}, pendingEscrow: {} };
  try {
    const raw = world.getDynamicProperty(STORAGE);
    if (!raw || typeof raw !== "string") return fallback;
    const parsed = JSON.parse(raw);
    return { ...fallback, ...parsed, settings: { ...fallback.settings, ...(parsed.settings ?? {}), itemOverrides: { ...(fallback.settings.itemOverrides ?? {}), ...(parsed.settings?.itemOverrides ?? {}) } }, arena: { ...fallback.arena, ...(parsed.arena ?? {}) }, pendingEscrow: parsed.pendingEscrow ?? {} };
  } catch { return fallback; }
}
function saveState() { try { world.setDynamicProperty(STORAGE, JSON.stringify(state)); } catch {} }
function tell(p, msg) { try { p.sendMessage(PREFIX + msg); } catch {} }
function players() { return world.getAllPlayers(); }
function onlineByName(name) { return players().find(p => p.name.toLowerCase() === name.toLowerCase()); }
function inMatch(p) { return !!match && match.players.some(x => x?.id === p.id); }
function announce(msg) { if (match) for (const p of match.players) if (p) tell(p, msg); }
function stats(p) { return state.stats[p.id] ?? (state.stats[p.id] = { wins: 0, losses: 0, rounds: 0, steals: 0, kept: 0 }); }
function stat(p, key, amount = 1) {
  const s = stats(p); s[key] = (s[key] ?? 0) + amount;
  const marks = [
    ["wins",5,"Опытный игрок"],["wins",10,"Мастер обмана"],["wins",25,"Легенда доверия"],
    ["steals",10,"Искусный вор"],["rounds",25,"Ветеран арены"]
  ];
  for (const [field, threshold, title] of marks) {
    const id = field + ":" + threshold;
    if (key === field && s[field] >= threshold && !state.achievements[p.id + ":" + id]) {
      state.achievements[p.id + ":" + id] = true;
      tell(p, "§6Достижение открыто: " + title + "!");
    }
  }
  saveState();
}
function give(p, typeId, amount = 1) {
  try {
    const inv = p.getComponent("minecraft:inventory")?.container;
    const item = new ItemStack(typeId, Math.max(1, amount));
    const left = inv?.addItem(item);
    if (!inv || left) p.dimension.spawnItem(left ?? item, p.location);
    if (typeId === "minecraft:bow" || typeId === "minecraft:crossbow") {
      const arrows = new ItemStack("minecraft:arrow", 16);
      const remainder = inv?.addItem(arrows);
      if (!inv || remainder) p.dimension.spawnItem(remainder ?? arrows, p.location);
    }
  } catch { tell(p, "Предмет не поддерживается этой версией: " + typeId); }
}
function removeOneFromHand(p) {
  const inv = p.getComponent("minecraft:inventory")?.container;
  if (!inv) return null;
  const slot = p.selectedSlotIndex;
  const item = inv.getItem(slot);
  if (!item) return null;
  const entry = { typeId: item.typeId, amount: 1 };
  if (item.amount <= 1) inv.setItem(slot, undefined);
  else { item.amount--; inv.setItem(slot, item); }
  return entry;
}
function returnEscrow() {
  if (!match) return;
  for (const [id, items] of Object.entries(match.escrow ?? {})) {
    const p = match.players.find(x => x?.id === id);
    if (p) for (const item of items) give(p, item.typeId, item.amount);
    delete state.pendingEscrow[id];
  }
  saveState();
  match.escrow = {};
}
function weightedClass() {
  const enabled = state.settings.enabledClasses.filter(c => (state.settings.weights[c] ?? 0) > 0);
  const total = enabled.reduce((n, c) => n + state.settings.weights[c], 0);
  if (!total) return "COMMON";
  let pick = Math.random() * total;
  for (const c of enabled) { pick -= state.settings.weights[c]; if (pick <= 0) return c; }
  return enabled[0];
}
function classifyItem(id) {
  const override = state.settings.itemOverrides?.[id];
  if (override && CLASSES.includes(override)) return override;
  const path = id.split(":").pop().toLowerCase();
  const excluded = ["stairs","slab","fence","fence_gate","door","trapdoor","carpet","banner","torch","lantern","flower","glass","wool","concrete","terracotta","pane","button","pressure_plate","sign","sapling","leaves","planks","log","wall","candle","coral","pot"];
  if (excluded.some(word => path.includes(word))) return null;
  if (/netherite.*(sword|pickaxe|axe|shovel|hoe|helmet|chestplate|leggings|boots)/.test(path)) return "NETHERITE_GEAR";
  if (/diamond.*(sword|pickaxe|axe|shovel|hoe|helmet|chestplate|leggings|boots)/.test(path)) return "DIAMOND_GEAR";
  if (/iron.*(sword|pickaxe|axe|shovel|hoe|helmet|chestplate|leggings|boots)/.test(path)) return "IRON_GEAR";
  if (/(potion|stew|soup|bread|apple|carrot|potato|beef|pork|chicken|mutton|fish|salmon|cod|cookie|cake|pie|honey|milk|golden_carrot)/.test(path)) return "POTIONS_FOOD";
  if (/(elytra|totem|trident|nether_star|heart_of_the_sea|conduit|shulker_shell|echo_shard|dragon_breath)/.test(path)) return "RARE";
  if (/(netherite_ingot|beacon|heavy_core|enchanted_golden_apple|unique|legendary)/.test(path)) return "LEGENDARY";
  if (/(sword|pickaxe|axe|shovel|hoe|helmet|chestplate|leggings|boots|bow|crossbow|shield)/.test(path)) return "IRON_GEAR";
  return "COMMON";
}
function getItemPool(className) {
  try {
    const all = ItemTypes.getAll().map(item => item.id);
    const filtered = all.filter(id => classifyItem(id) === className);
    if (filtered.length) return filtered;
  } catch {}
  return POOLS[className] ?? POOLS.COMMON;
}
function chooseItem(className) {
  const pool = getItemPool(className);
  for (let i = 0; i < Math.min(24, pool.length); i++) {
    const id = pool[Math.floor(Math.random() * pool.length)];
    try { return new ItemStack(id); } catch {}
  }
  return new ItemStack("minecraft:bread");
}
function randomReward() { const c = weightedClass(); return { className: c, item: chooseItem(c) }; }
function safeTeleport(p, point) {
  if (!point || !state.settings.arenaTeleport) return;
  try { p.teleport({ x: point.x + 0.5, y: point.y, z: point.z + 0.5 }, { dimension: world.getDimension(point.dimension ?? "minecraft:overworld"), rotation: { x: point.pitch ?? 0, y: point.yaw ?? 0 } }); } catch {}
}
function restorePlayer(p) {
  try {
    if (state.settings.restoreHealth) { const h = p.getComponent("minecraft:health"); if (h) h.resetToMaxValue(); }
    if (state.settings.restoreHunger) { const food = p.getComponent("minecraft:player.hunger"); if (food) food.setCurrentValue(20); }
  } catch {}
}
function cleanChest() {
  if (!chestData) return;
  try {
    const dim = world.getDimension(chestData.dimension);
    const block = dim.getBlock(chestData.pos);
    if (block?.typeId === "minecraft:chest") block.setType("minecraft:air");
  } catch {}
  chestData = undefined;
}
function spawnTrapChest() {
  if (!match || chestData) return;
  const p = match.players[0];
  if (!p) return;
  const center = match.players.length > 1 && match.players[1]
    ? { x: Math.floor((p.location.x + match.players[1].location.x) / 2), y: Math.floor(p.location.y), z: Math.floor((p.location.z + match.players[1].location.z) / 2) }
    : { x: Math.floor(p.location.x + 2), y: Math.floor(p.location.y), z: Math.floor(p.location.z + 1) };
  const dim = p.dimension;
  for (let dx = -2; dx <= 2; dx++) for (let dz = -2; dz <= 2; dz++) {
    try {
      const pos = { x: center.x + dx, y: center.y, z: center.z + dz };
      const block = dim.getBlock(pos), below = dim.getBlock({ x: pos.x, y: pos.y - 1, z: pos.z });
      if (block?.typeId === "minecraft:air" && below && below.typeId !== "minecraft:air") {
        block.setType("minecraft:chest");
        chestData = { dimension: dim.id, pos, opened: false };
        dim.playSound("random.chestclosed", pos);
        announce("§6Появился сундук-ловушка! Нажми на него, чтобы открыть.");
        return;
      }
    } catch {}
  }
  announce("Сундук-ловушка не смог появиться на этой поверхности.");
}
function openTrapChest(player, block) {
  if (!chestData || chestData.opened || block.location.x !== chestData.pos.x || block.location.y !== chestData.pos.y || block.location.z !== chestData.pos.z) return false;
  chestData.opened = true;
  const dim = player.dimension;
  try { dim.playSound("random.chestopen", chestData.pos); } catch {}
  system.runTimeout(() => {
    if (!match) { cleanChest(); return; }
    const roll = Math.floor(Math.random() * 5);
    if (roll === 0) {
      const reward = chooseItem("COMMON");
      try { dim.getBlock(chestData.pos).getComponent("minecraft:inventory")?.container.addItem(reward); } catch { give(player, reward.typeId); }
      announce(player.name + " открыл сундук с обычной наградой: " + reward.typeId + ".");
    } else if (roll === 1) {
      const c = Math.random() < 0.5 ? "RARE" : "LEGENDARY";
      const reward = chooseItem(c);
      try { dim.getBlock(chestData.pos).getComponent("minecraft:inventory")?.container.addItem(reward); } catch { give(player, reward.typeId); }
      announce(player.name + " открыл сундук с редкой наградой: " + reward.typeId + ".");
    } else if (roll === 2) {
      player.addEffect("poison", 100, { amplifier: 1, showParticles: true });
      announce(player.name + " попал в ядовитую ловушку сундука!");
    } else if (roll === 3) {
      try { dim.createExplosion({ x: chestData.pos.x + 0.5, y: chestData.pos.y + 0.5, z: chestData.pos.z + 0.5 }, 2.5, { breaksBlocks: false, causesFire: false }); } catch {}
      announce("Сундук оказался взрывной ловушкой!");
    } else announce("Сундук оказался пустым.");
    system.runTimeout(cleanChest, 200);
  }, 8);
  return true;
}
function roundEvent() {
  if (!match) return;
  const chance = match.chaosMode ? 0.75 : 0.28;
  if (Math.random() > chance) return;
  const event = Math.floor(Math.random() * 3);
  if (event === 0) {
    match.doubleReward = true;
    announce("§eСобытие: двойная награда в этом раунде!");
  } else if (event === 1) spawnTrapChest();
  else {
    match.reveal = true;
    const chooser = match.players[match.chooserIndex];
    if (chooser) tell(chooser, "§bСобытие: предмет раскрыт — " + match.reward.typeId + "!");
    announce("§bСобытие: Проницательность раскрыла предмет выбирающему.");
  }
}
function beginRound() {
  if (!match) return;
  cleanChest();
  if (match.round > match.rounds) { beginFinalPhase(); return; }
  match.phase = "claim";
  match.ownerIndex = match.bot ? ((match.round - 1) % 2) : (match.round - 1) % match.players.length;
  match.chooserIndex = match.bot ? (1 - match.ownerIndex) : (match.teamMode ? (match.ownerIndex + 2) % match.players.length : (match.ownerIndex + 1) % match.players.length);
  match.reward = randomReward().item;
  match.shieldUsed = false; match.insightUsed = false; match.doubleReward = false; match.reveal = false;
  match.claim = ""; match.wagerRound = {};
  const owner = match.players[match.ownerIndex], chooser = match.players[match.chooserIndex];
  announce("§6Раунд " + match.round + "/" + match.rounds + ". §rВладелец: " + (owner?.name ?? "—") + "; выбирающий: " + (chooser?.name ?? "ИИ") + ".");
  tell(owner, "Твой тайный предмет: " + match.reward.typeId + ". Заявись: !los claim <текст>. Решение можно будет сделать после заявления.");
  if (chooser) tell(chooser, "Соперник скрывает предмет. Используй !los insight или дождись решения.");
  roundEvent();
  if (match.bot && match.ownerIndex === 1) {
    match.claim = Math.random() < 0.5 ? "Это редкая находка, поверь мне" : "Обычный предмет, ничего особенного";
    match.phase = "decision";
    announce("ИИ заявляет: «" + match.claim + "».");
    tell(match.players[0], "Теперь твой выбор против ИИ: !los keep или !los steal.");
  } else if (match.bot) {
    system.runTimeout(() => {
      if (!match || match.phase !== "claim") return;
      match.claim = "Игрок не сделал заявление вовремя.";
      match.phase = "decision";
      match.botChoice = Math.random() < (match.reveal ? 0.7 : 0.45) ? "steal" : "keep";
      announce("ИИ принимает решение: " + (match.botChoice === "steal" ? "украсть" : "оставить") + ".");
      system.runTimeout(() => { if (match?.phase === "decision") resolveChoice(match.botChoice, match.players[0]); }, 30);
    }, 400);
  }
}
function submitClaim(p, text) {
  if (!match || match.phase !== "claim" || match.players[match.ownerIndex]?.id !== p.id) return tell(p, "Сейчас ты не владелец предмета.");
  match.claim = text || "Ничего не говорю";
  match.phase = "decision";
  announce(p.name + " заявляет: «" + match.claim + "».");
  const chooser = match.players[match.chooserIndex];
  if (chooser) tell(chooser, "Пора решить: !los keep или !los steal. Можно использовать !los insight.");
  if (match.bot) {
    const chance = match.reveal ? 0.75 : 0.48;
    match.botChoice = Math.random() < chance ? "steal" : "keep";
    system.runTimeout(() => resolveChoice(match.botChoice, p), 40);
  }
}
function resolveChoice(choice, chooser) {
  if (!match || match.phase !== "decision") return tell(chooser, "Сейчас нельзя принять решение.");
  if (match.bot) {
    if (chooser.id !== match.players[0].id) return;
  } else if (match.players[match.chooserIndex]?.id !== chooser.id) return tell(chooser, "Сейчас решение принимает другой игрок.");
  const owner = match.players[match.ownerIndex];
  const stolen = choice === "steal" && !match.shieldUsed;
  const effectiveChooser = match.bot && match.ownerIndex === 0 ? undefined : chooser;
  const effectiveOwner = match.bot && match.ownerIndex === 1 ? undefined : owner;
  const winner = stolen ? effectiveChooser : effectiveOwner;
  const amount = (match.highStakes ? 2 : 1) * (match.doubleReward ? 2 : 1);
  if (winner) {
    give(winner, match.reward.typeId, amount);
    match.earnedItems ??= [];
    match.earnedItems.push(match.reward.typeId);
  }
  if (stolen && (!match.bot || match.ownerIndex === 1)) stat(chooser, "steals");
  else if ((!stolen || match.shieldUsed) && owner) stat(owner, "kept");
  for (const p of match.players) if (p) stat(p, "rounds");
  announce(match.bot
    ? (match.ownerIndex === 0
      ? (stolen ? "ИИ украл награду у игрока!" : "Игрок сохранил свою награду.")
      : (stolen ? chooser.name + " украл предмет у ИИ!" : "ИИ сохранил свою награду."))
    : (stolen ? chooser.name + " украл награду у " + owner.name + "!" : owner.name + " сохранил награду."));
  announce("Получено: " + match.reward.typeId + " ×" + amount + ".");
  const wagers = Object.values(match.escrow ?? {}).flat();
  if (wagers.length) {
    if (winner) for (const item of wagers) give(winner, item.typeId, item.amount);
    else for (const p of match.players) if (p) for (const item of (match.escrow[p.id] ?? [])) give(p,item.typeId,item.amount);
  }
  state.pendingEscrow = {}; saveState();
  match.escrow = {};
  match.phase = "between";
  match.round++;
  system.runTimeout(() => beginRound(), 40);
}
function useAbility(p, name) {
  if (!match || (match.phase !== "claim" && match.phase !== "decision")) return tell(p, "Способность можно использовать только во время раунда.");
  if (name === "shield" && match.players[match.ownerIndex]?.id === p.id) {
    match.abilitiesUsed ??= {};
    if (match.abilitiesUsed[p.id + ":shield"]) return tell(p, "Щит уже использован в этом матче.");
    match.abilitiesUsed[p.id + ":shield"] = true;
    match.shieldUsed = true; tell(p, "§aЩит активирован: кража этого предмета будет отменена.");
  } else if (name === "insight" && match.players[match.chooserIndex]?.id === p.id) {
    match.abilitiesUsed ??= {};
    if (match.abilitiesUsed[p.id + ":insight"]) return tell(p, "Проницательность уже использована в этом матче.");
    match.abilitiesUsed[p.id + ":insight"] = true;
    match.insightUsed = true; tell(p, "§bПроницательность: тайный предмет — " + match.reward.typeId + ".");
  } else tell(p, "Эта способность сейчас недоступна твоей роли.");
}
function wager(p) {
  if (!match || (match.phase !== "claim" && match.phase !== "decision") || !inMatch(p)) return tell(p, "Ставку можно сделать только во время раунда.");
  if (match.escrow?.[p.id]?.length) return tell(p, "Ты уже сделал ставку в этом раунде.");
  const item = removeOneFromHand(p);
  if (!item) return tell(p, "Возьми предмет в основную руку, чтобы поставить его.");
  if (!match.escrow) match.escrow = {};
  match.escrow[p.id] = [item];
  state.pendingEscrow[p.id] = [item]; saveState();
  announce(p.name + " сделал ставку: " + item.typeId + ".");
}
function finishMatch(winner, loser, reason = "") {
  if (!match) return;
  cleanChest();
  returnEscrow();
  if (winner) {
    stat(winner, "wins");
    const s = stats(winner);
    const title = s.wins >= 25 ? "Легенда доверия" : s.wins >= 10 ? "Мастер обмана" : s.wins >= 5 ? "Опытный игрок" : "";
    if (title) tell(winner, "§6Новое звание: " + title + "!");
  }
  if (loser) stat(loser, "losses");
  announce("§6Матч завершён. " + (winner ? "Победитель: " + winner.name + ". " : "") + reason);
  match = undefined;
}
function beginFinalPhase() {
  if (!match) return;
  cleanChest();
  if (match.players.length === 1 || match.bot) {
    match.phase = "mob_choice";
    const p = match.players[0];
    const type = MOB_TYPES[Math.floor(Math.random() * MOB_TYPES.length)].replace("minecraft: ", "minecraft:");
    try {
      const mob = p.dimension.spawnEntity(type, state.arena.second ? { x: state.arena.second.x + 0.5, y: state.arena.second.y, z: state.arena.second.z + 0.5 } : { x: p.location.x + 5, y: p.location.y, z: p.location.z });
      match.finalMobId = mob.id;
      mob.nameTag = "Финальный соперник";
      mob.addEffect("strength", 20 * 60 * 10, { amplifier: 1, showParticles: true });
      mob.addEffect("resistance", 20 * 60 * 10, { amplifier: 1, showParticles: true });
      try {
        const gear = mob.getComponent("minecraft:equippable");
        const earned = match.earnedItems ?? [];
        const choose = (suffix, fallback) => earned.filter(id => id.endsWith(suffix)).sort((a,b) => (b.includes("netherite")?3:b.includes("diamond")?2:b.includes("iron")?1:0)-(a.includes("netherite")?3:a.includes("diamond")?2:a.includes("iron")?1:0))[0] ?? fallback;
        gear?.setEquipment("Mainhand", new ItemStack(choose("_sword", "minecraft:netherite_sword")));
        gear?.setEquipment("Head", new ItemStack(choose("_helmet", "minecraft:diamond_helmet")));
        gear?.setEquipment("Chest", new ItemStack(choose("_chestplate", "minecraft:diamond_chestplate")));
        gear?.setEquipment("Legs", new ItemStack(choose("_leggings", "minecraft:diamond_leggings")));
        gear?.setEquipment("Feet", new ItemStack(choose("_boots", "minecraft:diamond_boots")));
      } catch {}
      mob.addEffect("slowness", 20 * 60 * 10, { amplifier: 10, showParticles: false });
    } catch { tell(p, "Не удалось создать финального моба. Матч завершён без дуэли."); finishMatch(p, undefined); return; }
    announce("§cДевять раундов позади. Финальный моб ждёт решения: !los mob fight или !los mob leave.");
  } else {
    match.phase = "duel";
    for (const p of match.players) restorePlayer(p);
    announce("§cФинальная PvP-дуэль! Смерть или !los leave означает поражение.");
  }
}
function mobAction(p, action) {
  if (!match || match.phase !== "mob_choice" || match.players[0]?.id !== p.id) return tell(p, "Сейчас нет выбора финального моба.");
  if (action === "leave") { finishMatch(p, undefined, "Ты отказался от финального боя."); return; }
  try {
    const mob = p.dimension.getEntities({}).find(e => e.id === match.finalMobId);
    if (mob) { mob.removeEffect("slowness"); mob.removeEffect("weakness"); }
  } catch {}
  match.phase = "mob_fight";
  tell(p, "Бейся! Победа над мобом завершит матч.");
}
function queueJoin(p) {
  if (inMatch(p) || queue.includes(p.id)) return tell(p, "Ты уже в очереди или матче.");
  queue.push(p.id); tell(p, "Ты в очереди на матч 1 на 1.");
  if (queue.length >= 2) {
    const a = players().find(x => x.id === queue.shift()), b = players().find(x => x.id === queue.shift());
    if (a && b) startPvp([a,b], DEFAULT_ROUNDS);
  }
}
function startPvp(list, rounds, opts = {}) {
  if (match) return tell(list[0], "Сейчас уже идёт матч.");
  match = { players: list, rounds, round: 1, phase: "starting", bot: false, abilitiesUsed: {}, earnedItems: [], chaosMode: !!opts.chaos, highStakes: !!opts.highStakes, escrow: {} };
  list.forEach((p,i) => { safeTeleport(p, i === 0 ? state.arena.first : state.arena.second); restorePlayer(p); });
  announce("Матч начался! " + rounds + " раундов.");
  beginRound();
}
function startTeamMatch(starter) {
  const all = players();
  if (match) return tell(starter, "Сейчас уже идёт матч.");
  if (all.length < 4) return tell(starter, "Для режима 2 на 2 нужны четыре игрока в одном мире.");
  const list = [starter, ...all.filter(x => x.id !== starter.id).slice(0, 3)];
  match = { players: list, rounds: state.settings.rounds, round: 1, phase: "starting", bot: false, teamMode: true, escrow: {}, abilitiesUsed: {}, earnedItems: [], teams: [[list[0],list[1]],[list[2],list[3]]] };
  list.forEach((p,i) => { safeTeleport(p, i < 2 ? state.arena.first : state.arena.second); restorePlayer(p); });
  announce("Командный матч 2 на 2 начался! Команда A: " + list[0].name + ", " + list[1].name + ". Команда B: " + list[2].name + ", " + list[3].name + ".");
  beginRound();
}
function startBot(p, rounds = DEFAULT_ROUNDS, opts = {}) {
  if (match || inMatch(p)) return tell(p, "Ты уже в матче.");
  match = { players: [p, undefined], rounds, round: 1, phase: "starting", bot: true, abilitiesUsed: {}, earnedItems: [], chaosMode: !!opts.chaos, highStakes: !!opts.highStakes, escrow: {} };
  safeTeleport(p, state.arena.first); restorePlayer(p);
  tell(p, "Матч против ИИ начался! " + rounds + " раундов.");
  beginRound();
}
function showStats(p) {
  const s = stats(p);
  const earned = Object.keys(state.achievements).filter(key => key.startsWith(p.id + ":")).map(key => key.split(":").slice(1).join(":"));
  tell(p, "Статистика: победы " + s.wins + ", поражения " + s.losses + ", раунды " + s.rounds + ", кражи " + s.steals + ", сохранения " + s.kept + ".");
  tell(p, "Достижения: " + (earned.length ? earned.join(", ") : "пока нет") + ".");
}
function showLoot(p) {
  if (!inMatch(p)) return tell(p, "Ты не в матче. Награды уже находятся в инвентаре.");
  tell(p, "Все выигранные награды автоматически выдаются в инвентарь. Если он заполнен, предметы выпадают рядом.");
}
function setArena(p, which) {
  const loc = p.location;
  state.arena[which] = { x: Math.floor(loc.x), y: Math.floor(loc.y), z: Math.floor(loc.z), dimension: p.dimension.id, yaw: p.getRotation().y, pitch: p.getRotation().x };
  saveState(); tell(p, "Точка арены " + which + " сохранена.");
}
function showSettings(p) {
  const s = state.settings;
  new ModalFormData().title("Обман и доверие — настройки")
    .slider("Раундов в матче", 1, 30, 1, s.rounds)
    .toggle("Телепортировать на арену", s.arenaTeleport)
    .toggle("Восстанавливать здоровье", s.restoreHealth)
    .toggle("Восстанавливать голод", s.restoreHunger)
    .show(p).then(r => {
      if (r.canceled || !r.formValues) return;
      [s.rounds, s.arenaTeleport, s.restoreHealth, s.restoreHunger] = r.formValues;
      saveState(); tell(p, "Настройки сохранены.");
    }).catch(() => tell(p, "Не удалось открыть настройки."));
}
function showMenu(p) {
  const form = new ActionFormData().title("Обман и доверие").body("Выбери действие")
    .button("Играть против ИИ")
    .button("Быстрый матч (3 раунда)")
    .button("Хаос")
    .button("Высокие ставки")
    .button("Встать в очередь 1 на 1")
    .button("Командный матч 2 на 2")
    .button("Пригласить игрока")
    .button("Статистика")
    .button("Мои награды")
    .button("Справка");
  form.show(p).then(r => {
    if (r.canceled) return;
    if (r.selection === 0) startBot(p);
    else if (r.selection === 1) startBot(p, 3);
    else if (r.selection === 2) startBot(p, 9, { chaos: true });
    else if (r.selection === 3) startBot(p, 9, { highStakes: true });
    else if (r.selection === 4) queueJoin(p);
    else if (r.selection === 5) startTeamMatch(p);
    else if (r.selection === 6) showInvite(p);
    else if (r.selection === 7) showStats(p);
    else if (r.selection === 8) showLoot(p);
    else usage(p);
  }).catch(() => tell(p, "Не удалось открыть меню."));
}
function showInvite(p) {
  const others = players().filter(x => x.id !== p.id);
  if (!others.length) return tell(p, "Сейчас нет других игроков в мире.");
  new ModalFormData().title("Приглашение").dropdown("Кого пригласить?", others.map(x => x.name), 0)
    .show(p).then(r => {
      if (r.canceled) return;
      const target = others[r.formValues[0]];
      invites.set(target.id, p.id);
      tell(target, p.name + " приглашает тебя в «Обман и доверие». Напиши !los accept " + p.name + " или !los decline " + p.name + ".");
      tell(p, "Приглашение отправлено игроку " + target.name + ".");
    }).catch(() => tell(p, "Не удалось открыть список игроков."));
}
function acceptInvite(p, hostName) {
  const host = onlineByName(hostName), hostId = invites.get(p.id);
  if (!host || host.id !== hostId || match) return tell(p, "Приглашение не найдено или матч уже занят.");
  invites.delete(p.id);
  startPvp([host,p], state.settings.rounds);
}
function usage(p) {
  tell(p, "Команды: !los team — командный матч 2 на 2; !los class <класс>, !los weight <класс> <вес>.");
  tell(p, "Команды: !los menu, start [bot], quick, chaos, highstakes, join, invite <ник>, accept <ник>, decline <ник>, claim <текст>, keep, steal, ability shield|insight, wager, mob fight|leave, stats, loot, status, leave, setarena1, setarena2, settings, rounds <1-30>, classes, pool, inspect <id>, itemclass <id> <класс>.");
}
function command(p, raw) {
  const parts = raw.trim().split(/\s+/), cmd = (parts[1] ?? "help").toLowerCase();
  if (cmd === "help") usage(p);
  else if (cmd === "menu") showMenu(p);
  else if (cmd === "start") parts[2] === "bot" ? startBot(p) : (players().filter(x=>x.id!==p.id).length ? startPvp([p,players().find(x=>x.id!==p.id)], state.settings.rounds) : tell(p,"Нужен второй игрок. Используй !los start bot."));
  else if (cmd === "bot") startBot(p);
  else if (cmd === "quick") startBot(p,3);
  else if (cmd === "chaos") startBot(p,9,{chaos:true});
  else if (cmd === "highstakes") startBot(p,9,{highStakes:true});
  else if (cmd === "join") queueJoin(p);
  else if (cmd === "team" || cmd === "2v2") startTeamMatch(p);
  else if (cmd === "invite") { const t=onlineByName(parts[2]??""); if(!t)return tell(p,"Игрок не найден."); invites.set(t.id,p.id); tell(t,p.name+" приглашает тебя. !los accept "+p.name); tell(p,"Приглашение отправлено."); }
  else if (cmd === "accept") acceptInvite(p,parts[2]??"");
  else if (cmd === "decline") { const h=onlineByName(parts[2]??""); if(h)tell(h,p.name+" отклонил приглашение."); invites.delete(p.id); }
  else if (cmd === "claim") submitClaim(p,parts.slice(2).join(" "));
  else if (cmd === "keep" || cmd === "steal") resolveChoice(cmd,p);
  else if (cmd === "ability") useAbility(p,parts[2]);
  else if (cmd === "shield" || cmd === "insight") useAbility(p,cmd);
  else if (cmd === "wager") wager(p);
  else if (cmd === "mob") mobAction(p,parts[2]);
  else if (cmd === "stats") showStats(p);
  else if (cmd === "loot") showLoot(p);
  else if (cmd === "status") tell(p, match && inMatch(p) ? "Фаза: "+match.phase+", раунд "+match.round+"/"+match.rounds : "Ты не участвуешь в матче.");
  else if (cmd === "leave") {
    if (!match || !inMatch(p)) { queue=queue.filter(id=>id!==p.id); tell(p,"Ты вышел из очереди."); return; }
    if (match.teamMode) {
      const losingTeam=match.teams.find(team=>team.some(x=>x.id===p.id));
      const winningTeam=match.teams.find(team=>team!==losingTeam);
      for (const x of winningTeam) stat(x,"wins");
      for (const x of losingTeam) stat(x,"losses");
      announce("Команда "+winningTeam.map(x=>x.name).join(" и ")+" победила: "+p.name+" сдался.");
      returnEscrow(); cleanChest(); match=undefined;
    } else if (match.phase === "duel" || match.phase === "mob_fight") {
      const opponent=match.players.find(x=>x&&x.id!==p.id);
      finishMatch(opponent,p,p.name+" сдался.");
    } else { announce(p.name+" покинул матч."); returnEscrow(); cleanChest(); match=undefined; }
  }
  else if (cmd === "setarena1") setArena(p,"first");
  else if (cmd === "setarena2") setArena(p,"second");
  else if (cmd === "settings") showSettings(p);
  else if (cmd === "rounds") { const n=Math.max(1,Math.min(30,Number(parts[2])||9)); state.settings.rounds=n; saveState(); tell(p,"Число раундов: "+n); }
  else if (cmd === "class") {
    const name=(parts[2]??"").toUpperCase();
    if (!CLASSES.includes(name)) return tell(p,"Неизвестный класс: "+name);
    if (state.settings.enabledClasses.includes(name)) state.settings.enabledClasses=state.settings.enabledClasses.filter(x=>x!==name);
    else state.settings.enabledClasses.push(name);
    saveState(); tell(p,"Класс "+name+" обновлён.");
  }
  else if (cmd === "weight") {
    const name=(parts[2]??"").toUpperCase(), value=Number(parts[3]);
    if (!CLASSES.includes(name) || !Number.isFinite(value) || value<0 || value>100000) return tell(p,"Формат: !los weight <класс> <0-100000>");
    state.settings.weights[name]=value; saveState(); tell(p,"Вес "+name+" установлен: "+value);
  }
  else if (cmd === "inspect") {
    const id=parts[2]??"";
    let exists=false; try { exists=!!ItemTypes.get(id); } catch {}
    tell(p,exists ? id+" — класс "+(classifyItem(id)??"ИСКЛЮЧЁН") : "Предмет не найден: "+id);
  }
  else if (cmd === "itemclass") {
    const id=parts[2]??"", cls=(parts[3]??"").toUpperCase();
    if (!id.includes(":") || !CLASSES.includes(cls)) return tell(p,"Формат: !los itemclass namespace:item КЛАСС");
    state.settings.itemOverrides ??= {}; state.settings.itemOverrides[id]=cls; saveState(); tell(p,"Для "+id+" задан класс "+cls+".");
  }
  else if (cmd === "classes") tell(p,"Классы: "+CLASSES.join(", "));
  else if (cmd === "pool") tell(p,CLASSES.map(c=>c+": "+getItemPool(c).length+" предметов; вес "+state.settings.weights[c]).join("\n"));
  else tell(p,"Неизвестная команда. !los help");
}
world.beforeEvents.chatSend.subscribe(ev => {
  if (!ev.message.trim().toLowerCase().startsWith("!los")) return;
  ev.cancel = true;
  const p=ev.sender, raw=ev.message;
  system.run(()=>command(p,raw));
});
try {
  world.afterEvents.playerInteractWithBlock.subscribe(ev => {
    if (chestData && ev.block.typeId==="minecraft:chest" && ev.block.location.x===chestData.pos.x && ev.block.location.y===chestData.pos.y && ev.block.location.z===chestData.pos.z) {
      openTrapChest(ev.player,ev.block);
    }
  });
  world.beforeEvents.playerBreakBlock.subscribe(ev => {
    if (chestData && ev.block.location.x===chestData.pos.x && ev.block.location.y===chestData.pos.y && ev.block.location.z===chestData.pos.z) ev.cancel=true;
  });
} catch {}
world.afterEvents.playerLeave.subscribe(ev => {
  if (!match) return;
  const p=match.players.find(x=>x?.id===ev.playerId);
  if (p) { announce(p.name+" отключился. Матч завершён."); returnEscrow(); cleanChest(); match=undefined; }
  queue=queue.filter(id=>id!==ev.playerId);
});
world.afterEvents.entityDie.subscribe(ev => {
  if (!match) return;
  const dead=ev.deadEntity;
  if (match.phase==="duel" && dead.typeId==="minecraft:player") {
    const loser=match.players.find(p=>p?.id===dead.id);
    if (match.teamMode) {
      const deadTeam = match.teams.find(team => team.some(p=>p.id===dead.id));
      const otherTeam = match.teams.find(team => team !== deadTeam);
      const deadTeamAlive = deadTeam.some(p => p.id!==dead.id && p.getComponent("minecraft:health")?.currentValue > 0);
      const otherAlive = otherTeam.filter(p => p.getComponent("minecraft:health")?.currentValue > 0);
      if (!deadTeamAlive && otherAlive.length) {
        for (const p of otherAlive) stat(p,"wins");
        for (const p of deadTeam) stat(p,"losses");
        announce("Команда " + otherAlive.map(p=>p.name).join(" и ") + " победила в финальной дуэли 2 на 2!");
        cleanChest(); returnEscrow(); match=undefined;
      }
    } else {
      const winner=match.players.find(p=>p&&p.id!==dead.id);
      if (loser) finishMatch(winner,loser,"Победа в финальной дуэли.");
    }
  } else if (match.phase==="mob_fight" && dead.id===match.finalMobId) finishMatch(match.players[0],undefined,"Финальный моб повержен!");
  else if (match.phase==="mob_fight" && dead.typeId==="minecraft:player" && dead.id===match.players[0]?.id) finishMatch(undefined,match.players[0],"Игрок проиграл финальному мобу.");
});
world.afterEvents.playerSpawn.subscribe(ev => {
  if (ev.initialSpawn) {
    const pending = state.pendingEscrow[ev.player.id];
    if (pending?.length) {
      system.runTimeout(() => {
        for (const item of pending) give(ev.player,item.typeId,item.amount);
        delete state.pendingEscrow[ev.player.id]; saveState();
        tell(ev.player,"Возвращена ставка незавершённого матча после перезапуска.");
      }, 40);
    }
    system.runTimeout(()=>tell(ev.player,"Аддон загружен! Открой меню командой !los menu или напиши !los help."),30);
  }
});
system.runTimeout(()=>{ for (const p of players()) tell(p,"«Обман и доверие» загружен. !los menu — открыть меню."); },40);
