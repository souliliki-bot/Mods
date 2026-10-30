import { world, system, ItemStack } from "@minecraft/server";
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
  const fallback = { stats: {}, settings: { rounds: DEFAULT_ROUNDS, arenaTeleport: false, restoreHealth: true, restoreHunger: true, chaos: false, enabledClasses: CLASSES.slice(), weights: { COMMON: 50, IRON_GEAR: 20, DIAMOND_GEAR: 12, NETHERITE_GEAR: 4, POTIONS_FOOD: 7, RARE: 5, LEGENDARY: 2 } }, arena: { first: null, second: null }, achievements: {} };
  try {
    const raw = world.getDynamicProperty(STORAGE);
    if (!raw || typeof raw !== "string") return fallback;
    const parsed = JSON.parse(raw);
    return { ...fallback, ...parsed, settings: { ...fallback.settings, ...(parsed.settings ?? {}) }, arena: { ...fallback.arena, ...(parsed.arena ?? {}) } };
  } catch { return fallback; }
}
function saveState() { try { world.setDynamicProperty(STORAGE, JSON.stringify(state)); } catch {} }
function tell(p, msg) { try { p.sendMessage(PREFIX + msg); } catch {} }
function players() { return world.getAllPlayers(); }
function onlineByName(name) { return players().find(p => p.name.toLowerCase() === name.toLowerCase()); }
function inMatch(p) { return !!match && match.players.some(x => x?.id === p.id); }
function announce(msg) { if (match) for (const p of match.players) if (p) tell(p, msg); }
function stats(p) { return state.stats[p.id] ?? (state.stats[p.id] = { wins: 0, losses: 0, rounds: 0, steals: 0, kept: 0 }); }
function stat(p, key, amount = 1) { const s = stats(p); s[key] = (s[key] ?? 0) + amount; saveState(); }
function give(p, typeId, amount = 1) {
  try {
    const inv = p.getComponent("minecraft:inventory")?.container;
    const item = new ItemStack(typeId, Math.max(1, amount));
    const left = inv?.addItem(item);
    if (!inv || left) p.dimension.spawnItem(left ?? item, p.location);
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
  }
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
function chooseItem(className) {
  const pool = POOLS[className] ?? POOLS.COMMON;
  for (let i = 0; i < 12; i++) {
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
      const reward = chooseItem("COMMON"); give(player, reward.typeId);
      announce(player.name + " нашёл в сундуке обычную награду: " + reward.typeId + ".");
    } else if (roll === 1) {
      const c = Math.random() < 0.5 ? "RARE" : "LEGENDARY";
      const reward = chooseItem(c); give(player, reward.typeId);
      announce(player.name + " нашёл редкую награду: " + reward.typeId + ".");
    } else if (roll === 2) {
      player.addEffect("poison", 100, { amplifier: 1, showParticles: true });
      announce(player.name + " попал в ядовитую ловушку сундука!");
    } else if (roll === 3) {
      try { dim.createExplosion({ x: chestData.pos.x + 0.5, y: chestData.pos.y + 0.5, z: chestData.pos.z + 0.5 }, 2.5, { breaksBlocks: false, causesFire: false }); } catch {}
      announce("Сундук оказался взрывной ловушкой!");
    } else announce("Сундук оказался пустым.");
    system.runTimeout(cleanChest, 24);
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
    announce("§bСобытие: предмет раскрыт всем — " + match.reward.typeId + "!");
  }
}
function beginRound() {
  if (!match) return;
  cleanChest();
  if (match.round > match.rounds) { beginFinalPhase(); return; }
  match.phase = "claim";
  match.ownerIndex = match.bot ? 0 : (match.round - 1) % match.players.length;
  match.chooserIndex = match.bot ? 1 : (match.ownerIndex + 1) % match.players.length;
  match.reward = randomReward().item;
  match.shieldUsed = false; match.insightUsed = false; match.doubleReward = false; match.reveal = false;
  match.claim = ""; match.wagerRound = {};
  const owner = match.players[match.ownerIndex], chooser = match.players[match.chooserIndex];
  announce("§6Раунд " + match.round + "/" + match.rounds + ". §rВладелец: " + (owner?.name ?? "—") + "; выбирающий: " + (chooser?.name ?? "ИИ") + ".");
  tell(owner, "Твой тайный предмет: " + match.reward.typeId + ". Заявись: !los claim <текст>. Решение можно будет сделать после заявления.");
  if (chooser) tell(chooser, "Соперник скрывает предмет. Используй !los insight или дождись решения.");
  roundEvent();
  if (match.bot) {
    system.runTimeout(() => {
      if (!match || match.phase !== "claim") return;
      const botClaim = Math.random() < 0.5 ? "Это ценный предмет" : "Обычная вещь, поверь мне";
      match.claim = botClaim;
      announce("ИИ заявляет: «" + botClaim + "».");
      match.phase = "decision";
      tell(match.players[0], "ИИ выбирает: " + (Math.random() < 0.45 ? "украсть" : "оставить") + ". Используй !los keep или !los steal.");
      match.botChoice = Math.random() < 0.45 ? "steal" : "keep";
    }, 40);
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
  const winner = match.bot ? owner : (stolen ? chooser : owner);
  const amount = (match.highStakes ? 2 : 1) * (match.doubleReward ? 2 : 1);
  if (!match.bot || !stolen) give(winner, match.reward.typeId, amount);
  if (stolen && !match.bot) stat(chooser, "steals"); else if (!stolen || match.shieldUsed) stat(owner, "kept");
  for (const p of match.players) if (p) stat(p, "rounds");
  announce(stolen ? chooser.name + " украл награду у " + owner.name + "!" : owner.name + " сохранил награду.");
  announce("Получено: " + match.reward.typeId + " ×" + amount + ".");
  const wagers = Object.values(match.escrow ?? {}).flat();
  if (wagers.length) for (const item of wagers) give(winner, item.typeId, item.amount);
  match.escrow = {};
  match.phase = "between";
  match.round++;
  system.runTimeout(() => beginRound(), 40);
}
function useAbility(p, name) {
  if (!match || (match.phase !== "claim" && match.phase !== "decision")) return tell(p, "Способность можно использовать только во время раунда.");
  if (name === "shield" && match.players[match.ownerIndex]?.id === p.id) {
    if (match.shieldUsed) return tell(p, "Щит уже использован в этом матче.");
    match.shieldUsed = true; tell(p, "§aЩит активирован: кража этого предмета будет отменена.");
  } else if (name === "insight" && match.players[match.chooserIndex]?.id === p.id) {
    if (match.insightUsed) return tell(p, "Проницательность уже использована в этом матче.");
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
  match = { players: list, rounds, round: 1, phase: "starting", bot: false, chaosMode: !!opts.chaos, highStakes: !!opts.highStakes, escrow: {} };
  list.forEach((p,i) => { safeTeleport(p, i === 0 ? state.arena.first : state.arena.second); restorePlayer(p); });
  announce("Матч начался! " + rounds + " раундов.");
  beginRound();
}
function startBot(p, rounds = DEFAULT_ROUNDS, opts = {}) {
  if (match || inMatch(p)) return tell(p, "Ты уже в матче.");
  match = { players: [p, undefined], rounds, round: 1, phase: "starting", bot: true, chaosMode: !!opts.chaos, highStakes: !!opts.highStakes, escrow: {} };
  safeTeleport(p, state.arena.first); restorePlayer(p);
  tell(p, "Матч против ИИ начался! " + rounds + " раундов.");
  beginRound();
}
function showStats(p) {
  const s = stats(p);
  tell(p, "Статистика: победы " + s.wins + ", поражения " + s.losses + ", раунды " + s.rounds + ", кражи " + s.steals + ", сохранения " + s.kept + ".");
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
    else if (r.selection === 5) showInvite(p);
    else if (r.selection === 6) showStats(p);
    else if (r.selection === 7) showLoot(p);
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
  tell(p, "Команды: !los menu, start [bot], quick, chaos, highstakes, join, invite <ник>, accept <ник>, decline <ник>, claim <текст>, keep, steal, ability shield|insight, wager, mob fight|leave, stats, loot, status, leave, setarena1, setarena2, settings, rounds <1-30>, classes, pool.");
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
    if (match.phase === "duel" || match.phase === "mob_fight") {
      const opponent=match.players.find(x=>x&&x.id!==p.id);
      finishMatch(opponent,p,p.name+" сдался.");
    } else { announce(p.name+" покинул матч."); returnEscrow(); cleanChest(); match=undefined; }
  }
  else if (cmd === "setarena1") setArena(p,"first");
  else if (cmd === "setarena2") setArena(p,"second");
  else if (cmd === "settings") showSettings(p);
  else if (cmd === "rounds") { const n=Math.max(1,Math.min(30,Number(parts[2])||9)); state.settings.rounds=n; saveState(); tell(p,"Число раундов: "+n); }
  else if (cmd === "classes") tell(p,"Классы: "+CLASSES.join(", "));
  else if (cmd === "pool") tell(p,CLASSES.map(c=>c+": "+POOLS[c].length+" предметов; вес "+state.settings.weights[c]).join("\n"));
  else tell(p,"Неизвестная команда. !los help");
}
world.beforeEvents.chatSend.subscribe(ev => {
  if (!ev.message.trim().toLowerCase().startsWith("!los")) return;
  ev.cancel = true;
  const p=ev.sender, raw=ev.message;
  system.run(()=>command(p,raw));
});
try {
  world.beforeEvents.playerInteractWithBlock.subscribe(ev => {
    if (chestData && ev.block.typeId==="minecraft:chest" && ev.block.location.x===chestData.pos.x && ev.block.location.y===chestData.pos.y && ev.block.location.z===chestData.pos.z) {
      ev.cancel=true;
      const p=ev.player;
      system.run(()=>openTrapChest(p,ev.block));
    }
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
    const winner=match.players.find(p=>p&&p.id!==dead.id);
    if (loser) finishMatch(winner,loser,"Победа в финальной дуэли.");
  } else if (match.phase==="mob_fight" && dead.id===match.finalMobId) finishMatch(match.players[0],undefined,"Финальный моб повержен!");
});
world.afterEvents.playerSpawn.subscribe(ev => {
  if (ev.initialSpawn) system.runTimeout(()=>tell(ev.player,"Аддон загружен! Открой меню командой !los menu или напиши !los help."),30);
});
system.runTimeout(()=>{ for (const p of players()) tell(p,"«Обман и доверие» загружен. !los menu — открыть меню."); },40);
