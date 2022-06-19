const { MessageEmbed } = require("discord.js");

module.exports = function(message, commandName, client) {
  const command = client.commands.get(commandName) || client.commands.find((cmd) => cmd.config.aliases && cmd.config.aliases.includes(commandName));
  if (!command || command.config.help === false) return;
  var cmd = command.config;
  var embed = new MessageEmbed()
  .setColor('#ff0871')
  .setTitle('Command: ' + cmd.name)
  if (cmd.description) embed.setDescription(cmd.description)
  if (cmd.aliases) embed.addField('Aliases:', client.prefix + cmd.aliases.join(", " + client.prefix));
  if (cmd.usage) embed.addField((cmd.usage.length > 1 ? 'Usages:' : 'Usage:'), client.prefix + cmd.usage.join('\n' + client.prefix));
  if (cmd.examples) {
    var examples = client.prefix + cmd.examples.join("\n" + client.prefix);
    
    examples = examples.replace(/\{user\}/g, message.author).replace(/\{userID\}/g, message.author.id);
    examples = examples.replace(/\{bot\}/g, '<@' + client.user.id + '>');
    
    embed.addField((cmd.examples.length > 1 ? 'Examples:' : 'Example:'), examples);
  }
  
  return embed;
}