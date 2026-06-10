@ECHO OFF

java -Dlog4j2.configurationFile="log4j2.xml" --add-opens java.base/java.io=ALL-UNNAMED -jar p2pstorage.jar %1 %2 %3 %4 %5 %6 %7 %8