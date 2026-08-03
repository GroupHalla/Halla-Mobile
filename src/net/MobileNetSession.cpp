#include "MobileNetSession.h"
#include "MobileVoiceEngine.h"
#include <QJsonDocument>
#include <QVariantMap>
#include <QHostAddress>

MobileNetSession::MobileNetSession(QObject* parent) : QObject(parent) {
    m_tcp = new QTcpSocket(this);
    m_pingTimer = new QTimer(this);
    m_pingTimer->setInterval(15000);

    m_udp = new QUdpSocket(this);
    m_udp->bind();

    m_voiceEngine = new MobileVoiceEngine(this, this);

    connect(m_tcp, &QTcpSocket::connected, this, &MobileNetSession::onConnected);
    connect(m_tcp, &QTcpSocket::disconnected, this, &MobileNetSession::onDisconnected);
    connect(m_tcp, &QTcpSocket::readyRead, this, &MobileNetSession::onReadyRead);
    connect(m_udp, &QUdpSocket::readyRead, this, &MobileNetSession::onUdpReadyRead);
    connect(m_pingTimer, &QTimer::timeout, this, &MobileNetSession::onPingTimer);
}

MobileNetSession::~MobileNetSession() {
    disconnectFromServer();
}

QObject* MobileNetSession::voiceEngine() const {
    return m_voiceEngine;
}

QVariantList MobileNetSession::channels() const {
    QVariantList list;
    for (const Channel& c : m_data.channels) {
        QVariantMap map;
        map["id"] = c.id;
        map["parentId"] = c.parentId;
        map["name"] = c.name;
        map["topic"] = c.topic;
        map["type"] = c.type;
        list << map;
    }
    return list;
}

QVariantList MobileNetSession::users() const {
    QVariantList list;
    for (const User& u : m_data.users) {
        QVariantMap map;
        map["id"] = u.id;
        map["name"] = u.name;
        map["channelId"] = m_data.channelOfUser(u.id);
        map["talking"] = u.talking;
        map["whispering"] = u.whispering;
        map["sigla"] = u.sigla;
        map["groupIcon"] = u.groupIcon;
        list << map;
    }
    return list;
}

void MobileNetSession::connectToServer(const QString& host, int port, const QString& nick, const QString& password) {
    m_host = host;
    m_nick = nick;
    m_password = password;
    m_tcp->connectToHost(host, quint16(port));
}

void MobileNetSession::disconnectFromServer() {
    if (m_connected) {
        m_tcp->disconnectFromHost();
    }
}

void MobileNetSession::joinChannel(int channelId) {
    QJsonObject m = HProto::msg("move");
    m["channel"] = channelId;
    send(m);
}

void MobileNetSession::sendChat(const QString& text) {
    if (text.trimmed().isEmpty()) return;
    QJsonObject m = HProto::msg("chat");
    m["scope"] = "channel";
    m["text"] = text;
    send(m);
}

void MobileNetSession::sendVoiceFrame(const QByteArray& opus, quint16 seq) {
    if (!m_voiceToken || m_udpPort == 0) return;
    m_udp->writeDatagram(HProto::encodeVoiceClient(m_voiceToken, seq, opus),
                         QHostAddress(m_host), m_udpPort);
}

void MobileNetSession::onConnected() {
    m_connected = true;
    emit isConnectedChanged();

    QJsonObject m = HProto::msg("hello");
    m["proto"] = HProto::kProtoVersion;
    m["uid"] = "HALLAmobile0000000000000000000=";
    m["nick"] = m_nick;
    m["pass"] = m_password;
    m["ver"] = "1.0.0-mobile";
    m["platform"] = "Android/iOS";
    send(m);

    m_pingTimer->start();
}

void MobileNetSession::onDisconnected() {
    m_connected = false;
    m_pingTimer->stop();
    m_data = ServerData();
    m_chatHistory.clear();
    m_udpPort = 0;
    m_voiceToken = 0;
    emit isConnectedChanged();
    emit serverNameChanged();
    emit stateChanged();
    emit chatReceived();
}

void MobileNetSession::onReadyRead() {
    m_buffer.append(m_tcp->readAll());
    while (true) {
        int idx = m_buffer.indexOf('\n');
        if (idx == -1) break;
        QByteArray line = m_buffer.left(idx).trimmed();
        m_buffer.remove(0, idx + 1);
        if (line.isEmpty()) continue;

        QJsonDocument doc = QJsonDocument::fromJson(line);
        if (doc.isObject()) {
            handleMessage(doc.object());
        }
    }
}

void MobileNetSession::onUdpReadyRead() {
    while (m_udp->hasPendingDatagrams()) {
        QNetworkDatagram dg = m_udp->receiveDatagram();
        QByteArray data = dg.data();
        if (data.size() < 10 || memcmp(data.constData(), "HALL", 4) != 0) continue;
        quint32 fromId;
        quint16 seq;
        memcpy(&fromId, data.constData() + 4, 4);
        memcpy(&seq, data.constData() + 8, 2);
        m_voiceEngine->handleIncomingVoice(int(fromId), seq, data.mid(10));
    }
}

void MobileNetSession::onPingTimer() {
    send(HProto::msg("ping"));
}

void MobileNetSession::send(const QJsonObject& obj) {
    if (m_tcp->state() == QAbstractSocket::ConnectedState) {
        m_tcp->write(QJsonDocument(obj).toJson(QJsonDocument::Compact) + '\n');
    }
}

void MobileNetSession::handleMessage(const QJsonObject& obj) {
    QString t = obj["t"].toString();
    if (t == "error") {
        emit connectionFailed(obj["msg"].toString());
        disconnectFromServer();
        return;
    }

    if (t == "welcome") {
        m_data.selfId = obj["selfId"].toInt();
        QJsonObject srv = obj["server"].toObject();
        m_data.name = srv["name"].toString();
        m_data.motd = srv["motd"].toString();
        
        m_data.users.clear();
        for (const QJsonValue& v : obj["users"].toArray()) applyUserJson(v.toObject());
        m_data.channels.clear();
        for (const QJsonValue& v : obj["channels"].toArray()) applyChanJson(v.toObject());

        // Configuração de Portas UDP de Voz
        QJsonObject voice = obj["voice"].toObject();
        m_udpPort = quint16(voice["udp"].toInt());
        m_voiceToken = voice["token"].toString().toUInt();

        // Registra endpoint UDP
        if (m_voiceToken && m_udpPort) {
            m_udp->writeDatagram(HProto::encodeVoiceClient(m_voiceToken, 1, QByteArray()),
                                 QHostAddress(m_host), m_udpPort);
        }

        emit serverNameChanged();
        emit stateChanged();

        QVariantMap sysMsg;
        sysMsg["from"] = "Sistema";
        sysMsg["text"] = m_data.motd;
        m_chatHistory << sysMsg;
        emit chatReceived();
        return;
    }

    if (t == "voice_token") {
        m_udpPort = quint16(obj["udp"].toInt());
        m_voiceToken = obj["token"].toString().toUInt();
        if (m_voiceToken && m_udpPort) {
            m_udp->writeDatagram(HProto::encodeVoiceClient(m_voiceToken, 1, QByteArray()),
                                 QHostAddress(m_host), m_udpPort);
        }
        return;
    }

    if (t == "user_joined") {
        applyUserJson(obj["user"].toObject());
        emit stateChanged();
        return;
    }

    if (t == "user_left") {
        m_data.users.remove(obj["id"].toInt());
        emit stateChanged();
        return;
    }

    if (t == "user_moved") {
        int uid = obj["id"].toInt();
        int chan = obj["channel"].toInt();
        if (m_data.users.contains(uid)) {
            for (auto& c : m_data.channels) c.users.removeAll(uid);
            if (m_data.channels.contains(chan)) m_data.channels[chan].users << uid;
        }
        emit stateChanged();
        return;
    }

    if (t == "chan_update") {
        applyChanJson(obj["chan"].toObject());
        emit stateChanged();
        return;
    }

    if (t == "chan_removed") {
        m_data.channels.remove(obj["id"].toInt());
        emit stateChanged();
        return;
    }

    if (t == "user_state") {
        int id = obj["id"].toInt();
        if (m_data.users.contains(id)) {
            if (obj.contains("talking")) m_data.users[id].talking = obj["talking"].toBool();
            if (obj.contains("whispering")) m_data.users[id].whispering = obj["whispering"].toBool();
        }
        emit stateChanged();
        return;
    }

    if (t == "chat") {
        QVariantMap msg;
        msg["from"] = obj["fromName"].toString();
        msg["text"] = obj["text"].toString();
        m_chatHistory << msg;
        emit chatReceived();
        return;
    }
}

void MobileNetSession::applyUserJson(const QJsonObject& u) {
    User usr;
    usr.id = u["id"].toInt();
    usr.name = u["name"].toString();
    usr.uniqueId = u["uid"].toString();
    usr.sigla = u["sigla"].toString();
    usr.groupIcon = u["icon"].toString();
    usr.talking = u["talking"].toBool();
    usr.whispering = u["whispering"].toBool();
    m_data.users[usr.id] = usr;
}

void MobileNetSession::applyChanJson(const QJsonObject& c) {
    Channel ch;
    ch.id = c["id"].toInt();
    ch.parentId = c["parent"].toInt(0);
    ch.name = c["name"].toString();
    ch.topic = c["topic"].toString();
    ch.type = c["type"].toInt(2);
    ch.users.clear();
    for (const QJsonValue& v : c["users"].toArray()) ch.users << v.toInt();
    m_data.channels[ch.id] = ch;
}
