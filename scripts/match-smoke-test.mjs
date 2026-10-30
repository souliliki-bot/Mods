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

async function main() {
  const first = createTestBot('LeaveStealTestA');
  const second = createTestBot('LeaveStealTestB');

  try {
    await Promise.all([
      waitForSpawn(first, 'LeaveStealTestA'),
      waitForSpawn(second, 'LeaveStealTestB')
    ]);

    first.chat('/leaveorsteal join');
    await waitFor(first, message => message.includes('добавлен в очередь'), 'first player queue confirmation');
    second.chat('/leaveorsteal join');

    await Promise.all([
      waitFor(first, message => message.includes('Твой тайный предмет'), 'round owner secret item'),
      waitFor(second, message => message.includes('Сам предмет пока скрыт'), 'opponent round prompt')
    ]);

    first.chat('/leaveorsteal claim CI проверка игрового раунда');
    await waitFor(second, message => message.includes('CI проверка игрового раунда'), 'claim delivered to opponent');
    second.chat('/leaveorsteal keep');

    await Promise.all([
      waitFor(first, message => message.includes('Начинается финальная PvP-дуэль'), 'duel phase for first player'),
      waitFor(second, message => message.includes('Начинается финальная PvP-дуэль'), 'duel phase for second player')
    ]);

    const inventoryDeadline = Date.now() + 10000;
    while (first.inventory.items().length === 0 && Date.now() < inventoryDeadline) {
      await new Promise(resolve => setTimeout(resolve, 200));
    }
    if (first.inventory.items().length === 0) {
      throw new Error('Round reward was not delivered to the first player inventory.');
    }

    first.chat('/leaveorsteal leave');
    await Promise.all([
      waitFor(first, message => message.includes('Ты сдался и проиграл дуэль'), 'surrender loss message'),
      waitFor(second, message => message.includes('Соперник сдался'), 'duel win message')
    ]);

    console.log('MATCH_SMOKE_TEST_SUCCESS: queue, claim, keep, reward delivery, duel transition and surrender passed.');
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
