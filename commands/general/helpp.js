const { MessageEmbed } = require('discord.js');
module.exports.run = async(message, args, client) => {
  var prefix = client.prefix;var cmd = "robux";
  var embed = new MessageEmbed().setAuthor(`قائـمة الـمساعـدة`).setColor("RANDOM").setThumbnail('https://cdn.discordapp.com/attachments/862103245836910633/863946664347303936/image0.gif').setDescription(`${prefix}${cmd} price <العدد>\n`.replace(/Des/g,`ــــــــــــــــــــــــــــــــــــــــــــــــــــــــــــــ`));
  message.channel.send(embed);
}

module.exports.config = {
  name: 'helpp'
}