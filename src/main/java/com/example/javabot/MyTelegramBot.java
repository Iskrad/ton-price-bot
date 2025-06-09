package com.example.javabot;

import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class MyTelegramBot implements SpringLongPollingBot {

    private final UpdateConsumer updateConsumer;
    private final String botToken;

    public MyTelegramBot(UpdateConsumer updateConsumer) {
        this.updateConsumer = updateConsumer;
        this.botToken = loadTokenFromFile("token.txt");
    }

    private String loadTokenFromFile(String filename) {
        try {
            return Files.readString(Path.of(filename)).trim();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read bot token from file: " + filename, e);
        }
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return updateConsumer;
    }
}
