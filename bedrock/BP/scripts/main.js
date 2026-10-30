import { world, system, ItemStack } from "@minecraft/server";

const PREFIX = "§6[Обман и доверие]§r ";
const REWARDS = [
  "minecraft:bread", "minecraft:iron_ingot", "minecraft:gold_ingot",
  "minecraft:emerald", "minecraft:arrow", "minecraft:shield",
  "minecraft:iron_sword", "minecraft:bow", "minecraft:diamond",
  "minecraft:golden_apple", "minecraft:experience_bottle",
  "minecraft:ender_pearl", "minecraft:diamond_sword",
  "minecraft:enchanted_golden_apple"
];

let match = undefined;
const records = new Map();

function tell(player, message) {
  player.sendMessage(PREFIX + message);
}

function onlinePlayers() {
  return world.getAllPlayers();
}

function give(player, typeId, amount = 1) {
  try {
    const inventory = player.getComponent("minecraft:inventory")?.container;
    if (!inventory) {
      player.dimension.spawnItem(new ItemStack(typeId, amount), player.location);
      return;
    }
    const remainder = inventory.addItem(new ItemStack(typeId, amount));
    if (remainder) player.dimension.spawnItem(remainder, player.location);
  } catch (error) {
    tell(player, "Не удалось выдать предмет: " + typeId);
  }
}

function record(player, field) {
  const id = player.id;
  const value = records.get(id) ?? { wins: 0, losses: 0 };
  value[field]++;
  records.set(id, value);
}

function usage(player) {
  tell(player, "Команды:");
  tell(player, "!los start — начать матч с двумя игроками; !los start bot — матч против ИИ.");
  tell(player, "!los keep или !los steal — решение выбирающего.");
  tell(player, "!los claim <слова> — заявление владельца о предмете.");
  tell(player, "!los shield — один раз отменить кражу; !los insight — узнать предмет.");
  tell(player, "!los stats — статистика; !los leave — выйти из матча.");
}

function announce(message) {
  if (!match) return;
  for (const player of match.players) if (player) tell(player, message);
}

function beginRound() {
  if (!match) return;
  if (match.round > match.rounds) {
    finishMatch();
    return;
  }

  match.ownerIndex = (match.round - 1) % match.players.length;
  match.chooserIndex = (match.ownerIndex + 1) % match.players.length;
  match.reward = REWARDS[Math.floor(Math.random() * REWARDS.length)];
  match.shieldUsed = false;
  match.insightUsed = false;
  match.claim = "";
  match.phase = "choice";

  const owner = match.players[match.ownerIndex];
  const chooser = match.players[match.chooserIndex];
  announce("Раунд " + match.round + "/" + match.rounds + " начался!");
  tell(owner, "Твой тайный предмет: " + match.reward + ". Заявись командой !los claim <слова>.");
  if (chooser) tell(chooser, "Соперник выбирает предмет. Реши: !los keep или !los steal.");
  if (match.bot && chooser === undefined) runBotChoice();
}

function runBotChoice() {
  if (!match || !match.bot || match.phase !== "choice") return;
  const owner = match.players[0];
  const steals = Math.random() < 0.5;
  system.runTimeout(() => {
    if (!match || match.phase !== "choice") return;
    if (steals && !match.shieldUsed) {
      give(owner, match.reward, match.highStakes ? 2 : 1);
      tell(owner, "ИИ попытался украсть, но раунд завершился: предмет достался тебе.");
    } else {
      give(owner, match.reward, match.highStakes ? 2 : 1);
      tell(owner, "ИИ оставил предмет тебе.");
    }
    nextRound();
  }, 40);
}

function resolveChoice(choice, chooser) {
  if (!match || match.phase !== "choice") return;
  if (match.players[match.chooserIndex] !== chooser) {
    tell(chooser, "Сейчас решение принимает другой игрок.");
    return;
  }

  const owner = match.players[match.ownerIndex];
  const stolen = choice === "steal" && !match.shieldUsed;
  const recipient = stolen ? chooser : owner;
  const amount = match.highStakes ? 2 : 1;
  give(recipient, match.reward, amount);
  announce(stolen
    ? chooser.name + " украл предмет у " + owner.name + "!"
    : owner.name + " сохранил свой предмет.");
  announce("Награда: " + match.reward + " ×" + amount + ".");
  nextRound();
}

function nextRound() {
  if (!match) return;
  match.phase = "between";
  match.round++;
  system.runTimeout(() => beginRound(), 40);
}

function finishMatch() {
  if (!match) return;
  const players = match.players.filter(Boolean);
  for (const player of players) {
    tell(player, "Все раунды завершены! Это базовая версия Bedrock-переноса; финальная дуэль появится в следующем этапе.");
  }
  match = undefined;
}

function startMatch(starter, bot, highStakes = false) {
  if (match) {
    tell(starter, "Матч уже идёт. Завершите его командой !los leave.");
    return;
  }
  const players = onlinePlayers();
  if (bot) {
    match = { players: [starter, undefined], bot: true, rounds: 9, round: 1, highStakes, phase: "starting" };
  } else {
    const opponent = players.find(p => p.id !== starter.id);
    if (!opponent) {
      tell(starter, "Для матча 1 на 1 нужен второй игрок. Используй !los start bot для одиночной игры.");
      return;
    }
    match = { players: [starter, opponent], bot: false, rounds: 9, round: 1, highStakes, phase: "starting" };
  }
  announce("Матч начался! Девять раундов. Честно ли ты сыграешь?");
  beginRound();
}

function handleCommand(player, raw) {
  const parts = raw.trim().split(/\s+/);
  if (parts[0] !== "!los") return false;
  const command = (parts[1] ?? "help").toLowerCase();

  if (command === "help") usage(player);
  else if (command === "start") startMatch(player, parts[2] === "bot");
  else if (command === "highstakes") startMatch(player, parts[2] === "bot", true);
  else if (command === "stats") {
    const value = records.get(player.id) ?? { wins: 0, losses: 0 };
    tell(player, "Победы: " + value.wins + " | поражения: " + value.losses + ".");
  } else if (command === "leave") {
    if (!match || !match.players.includes(player)) tell(player, "Ты не участвуешь в матче.");
    else {
      announce(player.name + " покинул матч.");
      match = undefined;
    }
  } else if (command === "keep" || command === "steal") resolveChoice(command, player);
  else if (command === "claim") {
    if (!match || match.phase !== "choice" || match.players[match.ownerIndex] !== player) tell(player, "Сейчас ты не владелец предмета.");
    else {
      match.claim = parts.slice(2).join(" ");
      announce(player.name + " заявляет: «" + (match.claim || "ничего не говорит") + "».");
    }
  } else if (command === "shield") {
    if (!match || match.phase !== "choice" || match.players[match.ownerIndex] !== player) tell(player, "Щит можно включить только в своём раунде-владельце.");
    else if (match.shieldUsed) tell(player, "Щит уже использован в этом матче.");
    else {
      match.shieldUsed = true;
      tell(player, "Щит активирован: кража этого предмета будет отменена.");
    }
  } else if (command === "insight") {
    if (!match || match.phase !== "choice" || match.players[match.chooserIndex] !== player) tell(player, "Прозрение можно использовать только в своём раунде выбирающего.");
    else if (match.insightUsed) tell(player, "Прозрение уже использовано в этом матче.");
    else {
      match.insightUsed = true;
      tell(player, "Прозрение: тайный предмет — " + match.reward + ".");
    }
  } else tell(player, "Неизвестная команда. Напиши !los help.");
  return true;
}

world.beforeEvents.chatSend.subscribe((event) => {
  if (!event.message.trim().startsWith("!los")) return;
  event.cancel = true;
  const player = event.sender;
  const message = event.message;
  system.run(() => handleCommand(player, message));
});

world.afterEvents.playerLeave.subscribe((event) => {
  if (!match) return;
  const leaving = match.players.find(p => p?.id === event.playerId);
  if (leaving) {
    announce(leaving.name + " отключился. Матч остановлен.");
    match = undefined;
  }
});

world.afterEvents.playerSpawn.subscribe((event) => {
  if (event.initialSpawn) {
    system.runTimeout(() => {
      tell(event.player, "Добро пожаловать! Напиши !los help, чтобы увидеть команды режима.");
    }, 30);
  }
});

tellAllReady();

function tellAllReady() {
  system.runTimeout(() => {
    for (const player of onlinePlayers()) tell(player, "Аддон загружен. Напиши !los help.");
  }, 40);
}
