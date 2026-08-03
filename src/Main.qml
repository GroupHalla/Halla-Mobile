import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

ApplicationWindow {
    id: window
    visible: true
    width: 390
    height: 720
    title: qsTr("Halla Mobile")

    // Paleta de cores moderna (Tema Escuro padrão para Mobile)
    color: "#1E2125"

    StackView {
        id: stackView
        anchors.fill: parent
        initialItem: connectPage
    }

    Component {
        id: connectPage
        Page {
            background: Rectangle { color: "#1E2125" }

            ColumnLayout {
                anchors.centerIn: parent
                width: parent.width * 0.85
                spacing: 20

                // Logo / Banner
                ColumnLayout {
                    Layout.alignment: Qt.AlignHCenter
                    spacing: 5
                    Label {
                        text: "HALLA"
                        font.pixelSize: 42
                        font.bold: true
                        color: "#2E7FC4"
                        Layout.alignment: Qt.AlignHCenter
                    }
                    Label {
                        text: "Comunicação de Voz Móvel"
                        font.pixelSize: 14
                        color: "#8B959E"
                        Layout.alignment: Qt.AlignHCenter
                    }
                }

                // Campos de Entrada
                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 12

                    TextField {
                        id: nicknameField
                        placeholderText: "Apelido (Nickname)"
                        text: "HallaMobile"
                        Layout.fillWidth: true
                        background: Rectangle {
                            color: "#24272C"
                            border.color: nicknameField.activeFocus ? "#2E7FC4" : "#3E434A"
                            border.width: 1
                            radius: 6
                        }
                        color: "#DCDFE3"
                        padding: 12
                    }

                    TextField {
                        id: addressField
                        placeholderText: "Endereço do Servidor (IP)"
                        text: "127.0.0.1"
                        Layout.fillWidth: true
                        background: Rectangle {
                            color: "#24272C"
                            border.color: addressField.activeFocus ? "#2E7FC4" : "#3E434A"
                            border.width: 1
                            radius: 6
                        }
                        color: "#DCDFE3"
                        padding: 12
                    }

                    TextField {
                        id: portField
                        placeholderText: "Porta"
                        text: "9987"
                        Layout.fillWidth: true
                        background: Rectangle {
                            color: "#24272C"
                            border.color: portField.activeFocus ? "#2E7FC4" : "#3E434A"
                            border.width: 1
                            radius: 6
                        }
                        color: "#DCDFE3"
                        padding: 12
                    }

                    TextField {
                        id: passwordField
                        placeholderText: "Senha do Servidor (Opcional)"
                        echoMode: TextInput.Password
                        Layout.fillWidth: true
                        background: Rectangle {
                            color: "#24272C"
                            border.color: passwordField.activeFocus ? "#2E7FC4" : "#3E434A"
                            border.width: 1
                            radius: 6
                        }
                        color: "#DCDFE3"
                        padding: 12
                    }
                }

                // Botão Conectar
                Button {
                    id: connectButton
                    text: "CONECTAR"
                    Layout.fillWidth: true
                    contentItem: Text {
                        text: connectButton.text
                        font.pixelSize: 16
                        font.bold: true
                        color: "white"
                        horizontalAlignment: Text.AlignHCenter
                        verticalAlignment: Text.AlignVCenter
                    }
                    background: Rectangle {
                        color: connectButton.pressed ? "#1E415F" : "#2E7FC4"
                        radius: 6
                    }
                    onClicked: {
                        netSession.connectToServer(addressField.text, parseInt(portField.text), nicknameField.text, passwordField.text)
                    }
                }

                Label {
                    id: errorLabel
                    text: ""
                    color: "#D9534F"
                    font.pixelSize: 13
                    font.bold: true
                    Layout.alignment: Qt.AlignHCenter
                    visible: text !== ""
                }
            }
        }
    }

    Component {
        id: serverPage
        Page {
            background: Rectangle { color: "#1E2125" }

            header: ToolBar {
                background: Rectangle { color: "#2B2E33" }
                RowLayout {
                    anchors.fill: parent
                    anchors.margins: 10
                    Label {
                        text: netSession.serverName
                        font.pixelSize: 18
                        font.bold: true
                        color: "#DCDFE3"
                        Layout.fillWidth: true
                    }
                    Button {
                        text: "Sair"
                        contentItem: Text {
                            text: "Sair"
                            color: "white"
                            font.bold: true
                        }
                        background: Rectangle {
                            color: "#D9534F"
                            radius: 4
                        }
                        onClicked: {
                            netSession.disconnectFromServer()
                        }
                    }
                }
            }

            ColumnLayout {
                anchors.fill: parent
                spacing: 0

                // Barra de Status e Controle de Voz / DSP / Gravação Local
                RowLayout {
                    Layout.fillWidth: true
                    Layout.margins: 12
                    spacing: 12

                    // Indicador Visual VAD (Nível de Voz / Se está falando)
                    Rectangle {
                        width: 24
                        height: 24
                        radius: 12
                        color: netSession.voiceEngine.isTalking ? "#4CAF50" : "#3E434A"
                        border.color: "#555"
                        border.width: 1

                        // Indicador de nível dinâmico de volume (RMS)
                        Rectangle {
                            anchors.bottom: parent.bottom
                            anchors.horizontalCenter: parent.horizontalCenter
                            width: 14
                            height: (netSession.voiceEngine.voiceLevel / 100.0) * 14 + 2
                            radius: 7
                            color: "#81C784"
                            visible: !netSession.voiceEngine.isTalking
                        }
                    }

                    Label {
                        text: netSession.voiceEngine.isTalking ? "Transmitindo" : "Silencioso"
                        font.pixelSize: 13
                        font.bold: true
                        color: netSession.voiceEngine.isTalking ? "#4CAF50" : "#8B959E"
                        Layout.fillWidth: true
                    }

                    // Botão Silenciar Microfone
                    Button {
                        id: muteMicBtn
                        property bool muted: false
                        contentItem: Text {
                            text: muteMicBtn.muted ? "🔇" : "🎙️"
                            font.pixelSize: 16
                            horizontalAlignment: Text.AlignHCenter
                            verticalAlignment: Text.AlignVCenter
                        }
                        background: Rectangle {
                            color: muteMicBtn.muted ? "#D9534F" : "#3E434A"
                            radius: 6
                        }
                        Layout.preferredWidth: 38
                        Layout.preferredHeight: 38
                        onClicked: {
                            muted = !muted
                            netSession.voiceEngine.setTransmitEnabled(!muted)
                        }
                    }

                    // Botão Gravar Voz Localmente
                    Button {
                        id: recBtn
                        contentItem: Text {
                            text: netSession.voiceEngine.isRecording ? "⏹️ Gravando" : "🔴 Gravar"
                            color: "white"
                            font.bold: true
                            horizontalAlignment: Text.AlignHCenter
                            verticalAlignment: Text.AlignVCenter
                        }
                        background: Rectangle {
                            color: netSession.voiceEngine.isRecording ? "#D9534F" : "#2E7FC4"
                            radius: 6
                        }
                        Layout.preferredHeight: 38
                        onClicked: {
                            if (netSession.voiceEngine.isRecording) {
                                netSession.voiceEngine.stopRecording()
                            } else {
                                // Salva gravação WAV na raiz do app móvel
                                netSession.voiceEngine.startRecording("HallaVoiceRec.wav")
                            }
                        }
                    }
                }

                // Divisor
                Rectangle {
                    Layout.fillWidth: true
                    height: 1
                    color: "#3E434A"
                }

                // Árvore de Canais e Usuários
                ListView {
                    id: channelsListView
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    clip: true
                    model: netSession.channels

                    delegate: ItemDelegate {
                        width: channelsListView.width
                        height: 48
                        background: Rectangle { color: "transparent" }

                        contentItem: RowLayout {
                            anchors.fill: parent
                            anchors.leftMargin: 15
                            spacing: 10

                            // Ícone do Canal (Pasta)
                            Rectangle {
                                width: 24
                                height: 24
                                color: "#E8B23C"
                                radius: 4
                                Label {
                                    text: "#"
                                    color: "white"
                                    font.bold: true
                                    anchors.centerIn: parent
                                }
                            }

                            Label {
                                text: modelData.name
                                font.pixelSize: 15
                                font.bold: true
                                color: "#DCDFE3"
                                Layout.fillWidth: true
                            }
                        }

                        onClicked: {
                            netSession.joinChannel(modelData.id)
                        }
                    }
                }

                // Divisor
                Rectangle {
                    Layout.fillWidth: true
                    height: 1
                    color: "#3E434A"
                }

                // Chat do Canal
                ColumnLayout {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 220
                    spacing: 5
                    Layout.margins: 10

                    ListView {
                        id: chatListView
                        Layout.fillWidth: true
                        Layout.fillHeight: true
                        clip: true
                        model: netSession.chatMessages

                        delegate: ColumnLayout {
                            width: chatListView.width
                            spacing: 2
                            Label {
                                text: modelData.from
                                font.pixelSize: 11
                                font.bold: true
                                color: modelData.from === "Sistema" ? "#E8B23C" : "#2E7FC4"
                            }
                            Label {
                                text: modelData.text
                                font.pixelSize: 14
                                color: "#DCDFE3"
                                wrapMode: Text.Wrap
                                Layout.fillWidth: true
                            }
                        }

                        onCountChanged: {
                            chatListView.positionViewAtEnd()
                        }
                    }

                    // Envio de Mensagem
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 8

                        TextField {
                            id: chatInputField
                            placeholderText: "Escreva uma mensagem..."
                            Layout.fillWidth: true
                            background: Rectangle {
                                color: "#24272C"
                                border.color: "#3E434A"
                                border.width: 1
                                radius: 6
                            }
                            color: "#DCDFE3"
                            padding: 10
                            onAccepted: {
                                if (text.trim() !== "") {
                                    netSession.sendChat(text)
                                    text = ""
                                }
                            }
                        }

                        Button {
                            id: sendButton
                            text: "Enviar"
                            contentItem: Text {
                                text: "Enviar"
                                color: "white"
                                font.bold: true
                                horizontalAlignment: Text.AlignHCenter
                                verticalAlignment: Text.AlignVCenter
                            }
                            background: Rectangle {
                                color: "#2E7FC4"
                                radius: 6
                            }
                            Layout.preferredWidth: 70
                            Layout.preferredHeight: 38
                            onClicked: {
                                if (chatInputField.text.trim() !== "") {
                                    netSession.sendChat(chatInputField.text)
                                    chatInputField.text = ""
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    Connections {
        target: netSession
        function onIsConnectedChanged() {
            if (netSession.isConnected) {
                stackView.replace(serverPage)
            } else {
                stackView.replace(connectPage)
            }
        }
        function onConnectionFailed(reason) {
            var page = stackView.currentItem
            if (page && page.hasOwnProperty("errorLabel")) {
                page.errorLabel.text = reason
            }
        }
    }
}
