import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;

/**
 * Discord Bot for AITool - listens for commands in Discord channels
 * 
 * Commands:
 * - "RUN AI TOOL" or "!runai" - Triggers all AITool agents to run
 * - "AI STATUS" or "!aistatus" - Shows current agent status summary
 * 
 * Setup:
 * 1. Set environment variable: DISCORD_BOT_TOKEN=your_bot_token
 * 2. Invite bot to your server with Message Content Intent enabled
 */
public class DiscordBot extends ListenerAdapter {
    
    private static JDA jda;
    private static final String BOT_TOKEN = System.getenv("DISCORD_BOT_TOKEN");
    
    public static void initialize() {
        if (BOT_TOKEN == null || BOT_TOKEN.isBlank()) {
            System.out.println("[DiscordBot] Bot token not configured. Set DISCORD_BOT_TOKEN environment variable.");
            System.out.println("[DiscordBot] Discord bot commands will not be available.");
            return;
        }
        
        try {
            jda = JDABuilder.createDefault(BOT_TOKEN)
                .enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MESSAGES)
                .addEventListeners(new DiscordBot())
                .build();
            
            System.out.println("[DiscordBot] Discord bot started successfully!");
            System.out.println("[DiscordBot] Listening for commands: 'RUN AI TOOL', '!runai', 'AI STATUS', '!aistatus'");
        } catch (Exception e) {
            System.err.println("[DiscordBot] Failed to start Discord bot: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public static void shutdown() {
        if (jda != null) {
            jda.shutdown();
            System.out.println("[DiscordBot] Discord bot shut down.");
        }
    }
    
    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        // Ignore bot messages
        if (event.getAuthor().isBot()) return;
        
        String message = event.getMessage().getContentRaw().trim();
        String messageLower = message.toLowerCase();
        MessageChannel channel = event.getChannel();
        
        // Command: RUN AI TOOL
        if (messageLower.contains("run ai tool") || messageLower.equals("!runai")) {
            handleRunAITool(event, channel);
            return;
        }
        
        // Command: AI STATUS
        if (messageLower.contains("ai status") || messageLower.equals("!aistatus")) {
            handleAIStatus(event, channel);
            return;
        }
        
        // Command: AI HELP
        if (messageLower.equals("!aihelp") || messageLower.contains("ai tool help")) {
            handleHelp(channel);
            return;
        }
    }
    
    private void handleRunAITool(MessageReceivedEvent event, MessageChannel channel) {
        String user = event.getAuthor().getName();
        
        channel.sendMessage("🚀 **AITool Agents Starting...**\nTriggered by: " + user).queue();
        
        // Run agents asynchronously
        AIToolAgent.runAgentsAsync();
        
        // Send confirmation
        channel.sendMessage("✅ All agents are now running! Check results at `/aitool` page.").queue();
        
        System.out.println("[DiscordBot] RUN AI TOOL command received from: " + user);
    }
    
    private void handleAIStatus(MessageReceivedEvent event, MessageChannel channel) {
        try {
            AIToolAgent.AgentSystemState state = AIToolAgent.getSystemState();
            
            if (state == null || state.performance == null || state.performance.isEmpty()) {
                channel.sendMessage("📊 **AITool Status**\nNo agents have run yet.").queue();
                return;
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("📊 **AITool Agent Status**\n\n");
            
            int totalAgents = state.performance.size();
            int winningAgents = 0;
            double totalPL = 0;
            int totalTrades = 0;
            
            for (AIToolAgent.AgentPerformance perf : state.performance.values()) {
                totalTrades += perf.totalTrades;
                totalPL += perf.totalProfitLoss;
                if (perf.isWinning) winningAgents++;
            }
            
            sb.append("**Agents:** ").append(totalAgents).append(" (").append(winningAgents).append(" winning)\n");
            sb.append("**Total Trades:** ").append(totalTrades).append("\n");
            sb.append("**Total P/L:** $").append(String.format("%.2f", totalPL)).append("\n\n");
            
            // Top 3 performers
            sb.append("**Top Performers:**\n");
            state.performance.values().stream()
                .sorted((a, b) -> Double.compare(b.totalProfitLoss, a.totalProfitLoss))
                .limit(3)
                .forEach(p -> {
                    String icon = p.totalProfitLoss >= 0 ? "🟢" : "🔴";
                    sb.append(icon).append(" **").append(p.agentName != null ? p.agentName : p.agentId)
                      .append("** - $").append(String.format("%.2f", p.totalProfitLoss))
                      .append(" (").append(String.format("%.1f%%", p.winRate)).append(" win rate)\n");
                });
            
            channel.sendMessage(sb.toString()).queue();
            
        } catch (Exception e) {
            channel.sendMessage("❌ Error getting status: " + e.getMessage()).queue();
        }
    }
    
    private void handleHelp(MessageChannel channel) {
        String help = """
            🤖 **AITool Bot Commands**
            
            **Run Agents:**
            • `RUN AI TOOL` - Start all AITool agents
            • `!runai` - Same as above (shortcut)
            
            **Status:**
            • `AI STATUS` - Show agent performance summary
            • `!aistatus` - Same as above (shortcut)
            
            **Help:**
            • `!aihelp` - Show this help message
            """;
        channel.sendMessage(help).queue();
    }
}
