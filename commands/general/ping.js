const { MessageEmbed } = require('discord.js');

module.exports.run = async(message, args, client) => {
  message.channel.send("pong").then(m => {
   m.edit(`\`\`\`\nTime Taken: ${m.createdTimestamp - message.createdTimestamp} ms\nDiscord API: ${Math.round(client.ws.ping)} ms\n\`\`\``);
  });
}

module.exports.config = {
  name: 'ping',
  description: "test speed bot.",
  usage: ['ping'],
}