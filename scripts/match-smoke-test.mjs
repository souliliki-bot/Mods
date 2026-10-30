import mineflayer from 'mineflayer';

const HOST = process.env.MC_HOST ?? '127.0.0.1';
const PORT = Number(process.env.MC_PORT ?? 25565);
const VERSION = '1.21.1';
const bots = [];
const histories = new Map();

function createTestBot(username) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username, version: VERSION });
  histories.set(bot, []);
  bot.on('messagestr', message => {
    const text = String(message);
    histories.get(bot).push(text);
    console.log(`[${username}] ${text}`);
  });
  bot.on('kicked', reason => console.error(`[${username}] kicked:`, reason));
  bot.on('error', error => console.error(`[${username}] error:`, error));
  bots.push(bot);
  return bot;
}

function waitFor(bot, predicate, description, timeoutMs = 45000) {
  const history = histories.get(bot);
  const existing = history.find(predicate);
  if (existing) return Promise.resolve(existing);

  return new Promise((resolve, reject) => {
    const started = Date.now();
    const timer = setInterval(() => {
      const found = history.find(predicate);
      if (found) {
        clearInterval(timer);
        resolve(found);
      } else if (Date.now() - started > timeoutMs) {
        clearInterval(timer);
        reject(new Error(`Timed out waiting for ${description}. Recent messages: ${history.slice(-12).join(' | ')}`));
      }
    }, 200);
  });
}

function waitForSpawn(bot, username) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`${username} did not spawn in time`)), 90000);
    bot.once('spawn', () => {
      clearTimeout(timer);
      console.log(`[${username}] spawned`);
      resolve();
    });
  });
}

async function waitForRoundParticipants(first, second, round, label) {
  const ownerPredicate = message =>
    message.includes(`Раунд ${round}/9`) && message.includes('Твой тайный предмет');
  const chooserPredicate = message =>
    message.includes(`Раунд ${round}/9`) && message.includes('Соперник выбирает предмет');

  return new Promise((resolve, reject) => {
    const started = Date.now();
    const timer = setInterval(() => {
      const firstHistory = histories.get(first);
      const secondHistory = histories.get(second);
      const firstOwner = firstHistory.find(ownerPredicate);
      const secondOwner = secondHistory.find(ownerPredicate);
      const firstChooser = firstHistory.find(chooserPredicate);
      const secondChooser = secondHistory.find(chooserPredicate);

      if (firstOwner && secondChooser) {
        clearInterval(timer);
        resolve({ owner: first, chooser: second });
      } else if (secondOwner && firstChooser) {
        clearInterval(timer);
        resolve({ owner: second, chooser: first });
      } else if (Date.now() - started > 45000) {
        clearInterval(timer);
        reject(new Error(`Timed out identifying round ${round} roles for ${label}. A: ${firstHistory.slice(-8).join(' | ')}. B: ${secondHistory.slice(-8).join(' | ')}`));
      }
    }, 200);
  });
}
async function playNineRounds(first, second, decisions, label, joinQueue = true) {
  if (joinQueue) {
    first.chat('/leaveorsteal join');
    await waitFor(first, message => message.includes('добавлен в очередь'), `${label}: first player queue confirmation`);
    histories.get(first).length = 0;
    histories.get(second).length = 0;
    second.chat('/leaveorsteal join');
  }

  for (let round = 1; round <= 9; round++) {
    const { owner, chooser } = await waitForRoundParticipants(first, second, round, label);

    owner.chat(`/leaveorsteal claim ${label} заявление раунд ${round}`);
    await waitFor(chooser, message => message.includes(`${label} заявление раунд ${round}`), `${label}: round ${round} claim delivery`);
    await new Promise(resolve => setTimeout(resolve, 900));
    chooser.chat(`/leaveorsteal ${decisions[round - 1]}`);
    await new Promise(resolve => setTimeout(resolve, 900));
  }

  await Promise.all([
    waitFor(first, message => message.includes('Начинается финальная PvP-дуэль'), `${label}: duel phase for first player`),
    waitFor(second, message => message.includes('Начинается финальная PvP-дуэль'), `${label}: duel phase for second player`)
  ]);
}

async function main() {
  const first = createTestBot('LeaveStealTestA');
  const second = createTestBot('LeaveStealTestB');

  try {
    await Promise.all([
      waitForSpawn(first, 'LeaveStealTestA'),
      waitForSpawn(second, 'LeaveStealTestB')
    ]);

    const mixedDecisions = ['keep', 'keep', 'steal', 'keep', 'steal', 'keep', 'keep', 'keep', 'keep'];
    await playNineRounds(first, second, mixedDecisions, 'CI surrender match');

    const inventoryDeadline = Date.now() + 10000;
    while ((first.inventory.items().length === 0 || second.inventory.items().length === 0) && Date.now() < inventoryDeadline) {
      await new Promise(resolve => setTimeout(resolve, 200));
    }
    if (first.inventory.items().length === 0 || second.inventory.items().length === 0) {
      throw new Error(`Round rewards were not delivered to both players. Inventories: A=${first.inventory.items().length}, B=${second.inventory.items().length}`);
    }

    first.chat('/leaveorsteal leave');
    await Promise.all([
      waitFor(first, message => message.includes('Ты сдался и проиграл дуэль'), 'surrender loss message'),
      waitFor(second, message => message.includes('Соперник сдался'), 'duel win message')
    ]);

    histories.get(first).length = 0;
    histories.get(second).length = 0;
    first.chat('/leaveorsteal bot');
    await waitFor(first, message => message.includes('начал матч против ИИ-бота'), 'bot match start');
    for (let round = 1; round <= 9; round++) {
      if (round % 2 === 1) {
        await waitFor(first, message => message.includes(`Раунд ${round}/9`) && message.includes('Твой тайный предмет'), `bot match round ${round} human item`);
        first.chat(`/leaveorsteal claim CI бот заявление раунд ${round}`);
        await new Promise(resolve => setTimeout(resolve, 900));
        await waitFor(first, message => message.includes('Бот ') && (message.includes('украсть') || message.includes('поверил')), `bot decision in round ${round}`);
      } else {
        await waitFor(first, message => message.includes(`Раунд ${round}/9`) && message.includes('Бот выбрал предмет'), `bot item in round ${round}`);
        first.chat('/leaveorsteal keep');
        await new Promise(resolve => setTimeout(resolve, 900));
      }
    }
    await waitFor(first, message => message.includes('вышел моб-противник:') && message.includes('экипирован добытыми предметами'), 'spawned and equipped hostile mob duel');
    const mobDeadline = Date.now() + 10000;
    let duelMob;
    while (Date.now() < mobDeadline && !duelMob) {
      duelMob = Object.values(first.entities).find(entity =>
        entity !== first.entity && entity.type !== 'player' &&
        entity.position && entity.position.distanceTo(first.entity.position) < 24
      );
      if (!duelMob) await new Promise(resolve => setTimeout(resolve, 200));
    }
    if (!duelMob) {
      const nearby = Object.values(first.entities).map(entity => ({
        name: entity.name, type: entity.type, position: entity.position
      }));
      throw new Error('Physical mob opponent was not visible for right-click interaction. Tracked entities: ' + JSON.stringify(nearby));
    }
    await first.activateEntity(duelMob);
    await waitFor(first, message => message.includes('[Уйти]') && message.includes('[Сразиться]'), 'mob interaction menu');
    first.chat('/leaveorsteal mob fight');
    await waitFor(first, message => message.includes('Бой начался'), 'player starts mob duel');
    first.chat('/leaveorsteal leave');
    await waitFor(first, message => message.includes('Ты сдался и проиграл дуэль'), 'bot duel surrender and mob cleanup');

    histories.get(first).length = 0;
    histories.get(second).length = 0;
    await new Promise(resolve => setTimeout(resolve, 1200));
    first.chat('/leaveorsteal lobby create');
    await waitFor(first, message => message.includes('Лобби создано'), 'private lobby creation');
    await new Promise(resolve => setTimeout(resolve, 1200));
    first.chat('/leaveorsteal invite LeaveStealTestB');
    await waitFor(second, message => message.includes('приглашает тебя'), 'lobby invitation received');
    await new Promise(resolve => setTimeout(resolve, 1200));
    histories.get(first).length = 0;
    histories.get(second).length = 0;
    second.chat('/leaveorsteal accept LeaveStealTestA');
    await waitFor(first, message => message.includes('Лобби заполнено'), 'lobby invitation accepted');
    await playNineRounds(first, second, Array(9).fill('keep'), 'CI disconnect match', false);
    first.quit('Simulate disconnect during duel');
    await waitFor(second, message => message.includes('Соперник отключился во время финальной дуэли'), 'disconnect awards duel victory');

    console.log('MATCH_SMOKE_TEST_SUCCESS: human queue match, surrender, right-click mob menu, physical mob AI match, private lobby invitation/acceptance, nine rounds and duel disconnect passed.');
  } finally {
    for (const bot of bots) {
      try { bot.quit('CI test complete'); } catch {}
    }
  }
}

main().catch(error => {
  console.error('MATCH_SMOKE_TEST_FAILED:', error);
  process.exitCode = 1;
});
