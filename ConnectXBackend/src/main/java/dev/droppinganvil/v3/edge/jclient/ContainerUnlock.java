/*
 * Copyright (c) 2022. Christopher Willett
 * All Rights Reserved
 */

package dev.droppinganvil.v3.edge.jclient;

import dev.droppinganvil.v3.AnvilApp;
import dev.droppinganvil.v3.utils.obj.RequiresLogin;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.File;
import java.io.IOException;

public class ContainerUnlock {
    public static ContainerUnlock instance;
    public TextField id_entry;
    public PasswordField password_entry;
    public Button continue_button;
    public Label login_response;

    public static FXMLLoader getLoader() {
        return new FXMLLoader(dev.droppinganvil.v3.login.Login.class.getResource("Login.fxml"));
    }
    public static FXMLLoader getTestLoader() {
        try {
            return new FXMLLoader(new File("/UsLogin.fxml").toURL());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static void loadLoginPane(Stage primaryStage, RequiresLogin app) throws IOException {
        FXMLLoader fxmlLoader = getTestLoader();
        Parent root = fxmlLoader.load();
        instance = fxmlLoader.getController();
        primaryStage.setScene(new Scene(root, 500, 220));
        primaryStage.initStyle(StageStyle.UNDECORATED);
        primaryStage.show();
        instance.continue_button.setOnAction(new EventHandler<ActionEvent>() {
            @Override public void handle(ActionEvent e) {
                instance.login_response.setText("Decrypting...");
                app.attemptLogin(instance.id_entry.getText(), instance.password_entry.getText());
            }
        });
    }
}

