#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include "net/MobileNetSession.h"

// Entrada do Aplicativo Mobile Halla (Android / iOS)
int main(int argc, char *argv[]) {
    QGuiApplication app(argc, argv);

    QQmlApplicationEngine engine;

    MobileNetSession netSession;
    engine.rootContext()->setContextProperty("netSession", &netSession);

    const QUrl url(QStringLiteral("qrc:/qt/qml/HallaMobile/src/Main.qml"));
    QObject::connect(&engine, &QQmlApplicationEngine::objectCreated,
                     &app, [url](QObject *obj, const QUrl &objUrl) {
        if (!obj && url == objUrl)
            QCoreApplication::exit(-1);
    }, Qt::QueuedConnection);
    engine.load(url);

    return app.exec();
}
