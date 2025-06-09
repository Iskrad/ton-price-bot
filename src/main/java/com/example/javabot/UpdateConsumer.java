package com.example.javabot;

import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONObject;

@Component
public class UpdateConsumer implements LongPollingSingleThreadUpdateConsumer {

    private final TelegramClient telegramClient;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final Map<String, Double> lastPrices = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSentTime = new ConcurrentHashMap<>();
    private final Set<String> trackedChannels = ConcurrentHashMap.newKeySet();

    private final Pattern channelPattern = Pattern.compile("^@(\\w+)$");

    public UpdateConsumer() {
        String token = loadTokenFromFile("token.txt");
        this.telegramClient = new OkHttpTelegramClient(token);
    }

    private String loadTokenFromFile(String filename) {
        try {
            return Files.readString(Path.of(filename)).trim();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read bot token from file: " + filename, e);
        }
    }

    @Override
    public void consume(Update update) {
        var message = update.getMessage();
        if (message == null || message.getText() == null) return;

        String text = message.getText().trim();
        String[] parts = text.split("\\s+");
        String command = parts[0];

        if (command.equalsIgnoreCase("/start")) {
            sendMessage(message.getChatId(), """
                👋 Welcome to the TON Price Bot!

                💡 Available commands:
                /ton @channel       
                – Add a channel to the tracking list (do not start sending yet)
                
                /tonstart @channel  
                – Start sending TON price updates to the channel every 30 seconds
                
                /tonstop @channel   
                – Stop sending updates to the channel

                ℹ️ The bot sends a new price only if it has changed, or every 2 minutes if it's the same.
                ⚠️ Make sure the bot is an admin in the target channel!
                """);
            return;
        }

        // Для остальных команд проверяем, что есть 2 части (команда + @channel)
        if (parts.length != 2) return;

        String channelTag = parts[1];
        Matcher matcher = channelPattern.matcher(channelTag);
        if (!matcher.matches()) return;

        String channelUsername = channelTag;

        switch (command.toLowerCase()) {
            case "/ton" -> {
                if (trackedChannels.add(channelUsername)) {
                    sendMessage(message.getChatId(), "✅ Channel " + channelUsername + " added. Use /tonstart " + channelUsername + " to start updates.");
                } else {
                    sendMessage(message.getChatId(), "⚠️ Channel " + channelUsername + " is already added.");
                }
            }

            case "/tonstart" -> {
                if (!trackedChannels.contains(channelUsername)) {
                    sendMessage(message.getChatId(), "❌ Please add the channel first using /ton " + channelUsername);
                    return;
                }

                if (scheduledTasks.containsKey(channelUsername)) {
                    sendMessage(message.getChatId(), "⏳ Updates for " + channelUsername + " are already running.");
                    return;
                }

                ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
                    try {
                        double currentPrice = getTonPrice();
                        double lastPrice = lastPrices.getOrDefault(channelUsername, -1.0);
                        long lastSent = lastSentTime.getOrDefault(channelUsername, 0L);
                        long now = System.currentTimeMillis();

                        boolean changed = Double.compare(currentPrice, lastPrice) != 0;
                        boolean timeExceeded = (now - lastSent) >= 2 * 60 * 1000;

                        if (changed || timeExceeded) {
                            lastPrices.put(channelUsername, currentPrice);
                            lastSentTime.put(channelUsername, now);

                            SendMessage response = SendMessage.builder()
                                    .chatId(channelUsername)
                                    .text(String.format("TON Price: *%.2f\\$*", currentPrice))
                                    .parseMode("MarkdownV2")
                                    .build();

                            telegramClient.execute(response);
                        }

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }, 0, 30, TimeUnit.SECONDS);

                scheduledTasks.put(channelUsername, future);
                sendMessage(message.getChatId(), "🚀 Started sending updates to " + channelUsername);
            }

            case "/tonstop" -> {
                ScheduledFuture<?> task = scheduledTasks.remove(channelUsername);
                if (task != null) {
                    task.cancel(true);
                    sendMessage(message.getChatId(), "🛑 Stopped updates for " + channelUsername);
                } else {
                    sendMessage(message.getChatId(), "⚠️ No updates running for " + channelUsername);
                }
            }
        }
    }

    private double getTonPrice() throws IOException, InterruptedException {
        String url = "https://api.binance.com/api/v3/ticker/price?symbol=TONUSDT";

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        JSONObject json = new JSONObject(response.body());
        return json.getDouble("price");
    }

    private void sendMessage(Long chatId, String text) {
        try {
            SendMessage msg = SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .build();
            telegramClient.execute(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
