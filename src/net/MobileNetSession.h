#pragma once

#include <QObject>
#include <QTcpSocket>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QVariantList>
#include <QTimer>
#include "../core/Models.h"
#include "../../shared/HallaProtocol.h"

// Roteador de Rede Móvel Halla - Conecta o C++ ao QML (Android/iOS)
class MobileNetSession : public QObject {
    Q_OBJECT
    Q_PROPERTY(QString serverName READ serverName NOTIFY serverNameChanged)
    Q_PROPERTY(bool isConnected READ isConnected NOTIFY isConnectedChanged)
    Q_PROPERTY(QVariantList channels READ channels NOTIFY stateChanged)
    Q_PROPERTY(QVariantList users READ users NOTIFY stateChanged)
    Q_PROPERTY(int selfId READ selfId NOTIFY stateChanged)
    Q_PROPERTY(QVariantList chatMessages READ chatMessages NOTIFY chatReceived)

public:
    explicit MobileNetSession(QObject* parent = nullptr);
    ~MobileNetSession() override;

    QString serverName() const { return m_data.name; }
    bool isConnected() const { return m_connected; }
    int selfId() const { return m_data.selfId; }
    QVariantList channels() const;
    QVariantList users() const;
    QVariantList chatMessages() const { return m_chatHistory; }

    Q_INVOKABLE void connectToServer(const QString& host, int port, const QString& nick, const QString& password);
    Q_INVOKABLE void disconnectFromServer();
    Q_INVOKABLE void joinChannel(int channelId);
    Q_INVOKABLE void sendChat(const QString& text);

signals:
    void serverNameChanged();
    void isConnectedChanged();
    void stateChanged();
    void chatReceived();
    void connectionFailed(const QString& reason);

private slots:
    void onConnected();
    void onDisconnected();
    void onReadyRead();
    void onPingTimer();

private:
    void handleMessage(const QJsonObject& obj);
    void applyUserJson(const QJsonObject& u);
    void applyChanJson(const QJsonObject& c);
    void send(const QJsonObject& obj);

    QTcpSocket* m_tcp;
    bool m_connected = false;
    ServerData m_data;
    QByteArray m_buffer;
    QTimer* m_pingTimer;
    QVariantList m_chatHistory;
    QString m_nick;
    QString m_password;
};
