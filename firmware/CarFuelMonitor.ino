/*
 * Car Fuel Monitor - ESP32 v1.1.0
 * Fuel monitoring with auto-save and range calculation
 * Author: GradusXaker
 */

#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <BluetoothSerial.h>
#include <EEPROM.h>

// Пинов
#define FUEL_SENSOR_PIN 34
#define BUTTON_PIN 0
#define LED_PIN 2

// OLED
#define SCREEN_WIDTH 128
#define SCREEN_HEIGHT 64
#define OLED_RESET -1
#define SCREEN_ADDRESS 0x3C

// Bluetooth
#define BT_DEVICE_NAME "CarFuelMonitor"

// EEPROM адреса
#define EEPROM_SIZE 512
#define EEPROM_CALIB_FULL 0
#define EEPROM_CALIB_EMPTY 4
#define EEPROM_TANK_CAPACITY 8
#define EEPROM_FUEL_LEVEL 12
#define EEPROM_DISTANCE 16
#define EEPROM_TOTAL_USED 20
#define EEPROM_LAST_SAVE 24
#define EEPROM_MAGIC 28

#define EEPROM_MAGIC_VALUE 0x42

// Интервалы
#define UPDATE_INTERVAL 1000
#define AVG_SAMPLES 10
#define AUTO_SAVE_INTERVAL 60000  // Сохранение каждые 60 сек

// Глобальные объекты
Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, OLED_RESET);
BluetoothSerial SerialBT;

// Переменные
float fuelLevel = 0;
float fuelLiters = 0;
float fuelConsumption = 0;
float distanceTraveled = 0;
float totalFuelUsed = 0;
int calibFull = 4095;
int calibEmpty = 0;
float tankCapacity = 60;
float rangeKm = 0;           // Запас хода км
float avgConsumption = 0;    // Средний расход
unsigned long lastSaveTime = 0;
bool isCalibrating = false;
unsigned long calibrationStart = 0;
unsigned long lastUpdate = 0;
bool bluetoothConnected = false;
bool displayFound = false;
float phoneSpeed = 0;

// Чтение калибровки из EEPROM
void loadFromEEPROM() {
  // Проверка валидности данных
  if (EEPROM.readByte(EEPROM_MAGIC) != EEPROM_MAGIC_VALUE) {
    Serial.println(F("EEPROM empty"));
    return;
  }
  
  calibFull = EEPROM.readInt(EEPROM_CALIB_FULL);
  calibEmpty = EEPROM.readInt(EEPROM_CALIB_EMPTY);
  tankCapacity = EEPROM.readFloat(EEPROM_TANK_CAPACITY);
  fuelLevel = EEPROM.readFloat(EEPROM_FUEL_LEVEL);
  distanceTraveled = EEPROM.readFloat(EEPROM_DISTANCE);
  totalFuelUsed = EEPROM.readFloat(EEPROM_TOTAL_USED);
  lastSaveTime = EEPROM.readULong(EEPROM_LAST_SAVE);
  
  // Проверка на невалидные значения
  if (calibFull < 1000 || calibFull > 4095) calibFull = 4095;
  if (calibEmpty < 0 || calibEmpty > 1000) calibEmpty = 0;
  if (tankCapacity < 10 || tankCapacity > 200) tankCapacity = 60;
  if (fuelLevel < 0 || fuelLevel > 100) fuelLevel = 0;
  
  Serial.println("EEPROM loaded:");
  Serial.print("  Full: "); Serial.println(calibFull);
  Serial.print("  Empty: "); Serial.println(calibEmpty);
  Serial.print("  Tank: "); Serial.print(tankCapacity); Serial.println("L");
  Serial.print("  Fuel: "); Serial.print(fuelLevel, 1); Serial.println("%");
  Serial.print("  Distance: "); Serial.print(distanceTraveled, 0); Serial.println("km");
}

// Сохранение в EEPROM
void saveToEEPROM() {
  EEPROM.writeByte(EEPROM_MAGIC, EEPROM_MAGIC_VALUE);
  EEPROM.writeInt(EEPROM_CALIB_FULL, calibFull);
  EEPROM.writeInt(EEPROM_CALIB_EMPTY, calibEmpty);
  EEPROM.writeFloat(EEPROM_TANK_CAPACITY, tankCapacity);
  EEPROM.writeFloat(EEPROM_FUEL_LEVEL, fuelLevel);
  EEPROM.writeFloat(EEPROM_DISTANCE, distanceTraveled);
  EEPROM.writeFloat(EEPROM_TOTAL_USED, totalFuelUsed);
  EEPROM.writeULong(EEPROM_LAST_SAVE, millis() / 1000);
  EEPROM.commit();
  
  Serial.println(F("EEPROM saved"));
}

// Чтение датчика
int readFuelSensor() {
  int sum = 0;
  for (int i = 0; i < AVG_SAMPLES; i++) {
    sum += analogRead(FUEL_SENSOR_PIN);
    delay(5);
  }
  return sum / AVG_SAMPLES;
}

// Расчет уровня топлива
float calculateFuelLevel(int adcValue) {
  if (adcValue > calibFull) adcValue = calibFull;
  if (adcValue < calibEmpty) adcValue = calibEmpty;
  
  float range = calibFull - calibEmpty;
  if (range <= 0) range = 1;
  
  float percent = ((float)(adcValue - calibEmpty) / range) * 100.0;
  
  if (percent < 0) percent = 0;
  if (percent > 100) percent = 100;
  
  return percent;
}

// Расчет запаса хода
void calculateRange() {
  fuelLiters = (fuelLevel / 100.0) * tankCapacity;
  
  if (fuelConsumption > 0) {
    rangeKm = (fuelLiters / fuelConsumption) * 100.0;
  } else {
    rangeKm = 0;
  }
  
  // Расчет среднего расхода
  if (distanceTraveled > 10 && totalFuelUsed > 0) {
    avgConsumption = (totalFuelUsed / distanceTraveled) * 100.0;
  }
}

// Обновление данных
void updateFuelData() {
  int adcValue = readFuelSensor();
  float lastFuelLevel = fuelLevel;
  fuelLevel = calculateFuelLevel(adcValue);
  
  // Если уровень изменился больше чем на 1%
  if (abs(lastFuelLevel - fuelLevel) > 1.0) {
    fuelLiters = (fuelLevel / 100.0) * tankCapacity;
    
    // Если уровень упал - записываем расход
    if (lastFuelLevel > fuelLevel + 2.0) {  // +2% гистерезис
      float usedLiters = ((lastFuelLevel - fuelLevel) / 100.0) * tankCapacity;
      totalFuelUsed += usedLiters;
      Serial.print("Fuel used: "); Serial.print(usedLiters, 2); Serial.println("L");
    }
    // Если уровень вырос - это заправка
    else if (fuelLevel > lastFuelLevel + 5.0) {  // +5% считаем заправкой
      Serial.print("REFUEL: "); Serial.println(fuelLevel - lastFuelLevel, 1);
      // Не сбрасываем totalFuelUsed, сохраняем историю
    }
  }
  
  calculateRange();
  
  // Авто-сохранение
  if (millis() - lastSaveTime >= AUTO_SAVE_INTERVAL) {
    saveToEEPROM();
    lastSaveTime = millis();
  }
  
  Serial.printf("ADC: %d | Level: %.1f%% | Range: %.0f km\n", adcValue, fuelLevel, rangeKm);
}

// Расчет расхода
void calculateConsumption() {
  // Реальный пробег по скорости с телефона (км/ч)
  static unsigned long lastDistanceCalc = 0;
  if (lastDistanceCalc == 0) {
    lastDistanceCalc = millis();
  }

  unsigned long now = millis();
  float hours = (now - lastDistanceCalc) / 3600000.0;
  lastDistanceCalc = now;

  if (phoneSpeed > 0 && phoneSpeed < 300) {
    distanceTraveled += phoneSpeed * hours;

    // Сохранение примерно каждые 10 км
    static int lastSavedKm = 0;
    if (((int)distanceTraveled - lastSavedKm) >= 10) {
      saveToEEPROM();
      lastSavedKm = (int)distanceTraveled;
    }
  }
  
  if (distanceTraveled > 0 && totalFuelUsed > 0) {
    fuelConsumption = (totalFuelUsed / distanceTraveled) * 100.0;
  } else {
    fuelConsumption = 0;
  }
}

// Отрисовка
void drawDisplay() {
  if (!displayFound) return;
  
  if (isCalibrating) {
    display.clearDisplay();
    display.setCursor(0, 0);
    display.println("CALIBRATION");
    display.println("");
    display.print("ADC: ");
    display.println(readFuelSensor());
    display.print("Full: ");
    display.println(calibFull);
    display.print("Empty: ");
    display.println(calibEmpty);
    display.println("");
    display.println("Hold button:");
    display.println("1s = FULL");
    display.println("3s = HALF");
    display.println("5s = EMPTY");
    display.print("Time: ");
    display.print((millis() - calibrationStart) / 1000);
    display.print("s");
    display.display();
    return;
  }
  
  display.clearDisplay();
  
  // Строка 1: Bluetooth и время работы
  if (bluetoothConnected) {
    display.setCursor(0, 0);
    display.print("BT");
  }
  display.setCursor(20, 0);
  display.print(millis() / 60000);
  display.print("m");
  
  // Строка 2: Уровень топлива
  display.setCursor(60, 0);
  display.print((int)fuelLevel);
  display.print("%");
  
  // Бак
  display.drawRect(0, 12, 100, 25, SSD1306_WHITE);
  int fillWidth = (int)((98.0 * fuelLevel) / 100.0);
  display.fillRect(1, 13, fillWidth, 23, SSD1306_WHITE);
  
  // Литры
  display.setCursor(105, 12);
  display.print(fuelLiters, 0);
  display.print("L");
  
  // Запас хода
  display.setCursor(105, 24);
  display.print(rangeKm, 0);
  display.print("km");
  
  // Строка 3: Расход и пробег
  display.setCursor(0, 40);
  display.print("Avg: ");
  display.print(avgConsumption > 0 ? avgConsumption : fuelConsumption, 1);
  display.print("L/100km");
  
  display.setCursor(0, 52);
  display.print("Dist: ");
  display.print(distanceTraveled, 0);
  display.print("km");
  
  display.display();
}

// Bluetooth данные
void sendBluetoothData() {
  if (!bluetoothConnected) return;
  
  String json = "{";
  json += "\"fuel_level\":" + String(fuelLevel, 1);
  json += ",\"fuel_liters\":" + String(fuelLiters, 1);
  json += ",\"consumption\":" + String(fuelConsumption, 1);
  json += ",\"avg_consumption\":" + String(avgConsumption, 1);
  json += ",\"range_km\":" + String(rangeKm, 0);
  json += ",\"distance\":" + String(distanceTraveled, 1);
  json += ",\"total_used\":" + String(totalFuelUsed, 1);
  json += ",\"adc\":" + String(readFuelSensor());
  json += ",\"tank\":" + String(tankCapacity);
  json += ",\"calib_full\":" + String(calibFull);
  json += ",\"calib_empty\":" + String(calibEmpty);
  json += "}";
  SerialBT.println(json);
}

// Обработка команд
void processBluetoothCommand(String cmd) {
  Serial.println("BT: " + cmd);
  
  if (cmd == "GET_STATUS") {
    sendBluetoothData();
  }
  else if (cmd == "SET_FULL") {
    calibFull = readFuelSensor();
    if (calibFull <= calibEmpty + 100) {
      calibFull = calibEmpty + 100;
    }
    if (calibFull > 4095) calibFull = 4095;
    fuelLevel = 100;
    saveToEEPROM();
    SerialBT.println("OK: Full=" + String(calibFull));
  }
  else if (cmd == "SET_HALF") {
    int adcHalf = readFuelSensor();
    calibEmpty = adcHalf - (calibFull - adcHalf);
    if (calibEmpty < 0) calibEmpty = 0;
    if (calibEmpty >= calibFull - 100) calibEmpty = calibFull - 100;
    fuelLevel = 50;
    saveToEEPROM();
    SerialBT.println("OK: Half calibrated");
  }
  else if (cmd == "SET_EMPTY") {
    calibEmpty = readFuelSensor();
    if (calibEmpty >= calibFull - 100) {
      calibEmpty = calibFull - 100;
    }
    if (calibEmpty < 0) calibEmpty = 0;
    fuelLevel = 0;
    saveToEEPROM();
    SerialBT.println("OK: Empty=" + String(calibEmpty));
  }
  else if (cmd == "RESET_STATS") {
    distanceTraveled = 0;
    totalFuelUsed = 0;
    saveToEEPROM();
    SerialBT.println("OK: Stats reset");
  }
  else if (cmd.startsWith("SET_TANK:")) {
    float cap = cmd.substring(9).toFloat();
    if (cap > 10 && cap < 200) {
      tankCapacity = cap;
      saveToEEPROM();
      SerialBT.println("OK: Tank=" + String(cap) + "L");
    }
  }
  else if (cmd == "HELP") {
    SerialBT.println("Commands: GET_STATUS, SET_FULL, SET_HALF, SET_EMPTY, RESET_STATS, SET_TANK:XX, HELP");
  }
}

// Кнопка
void checkButton() {
  if (digitalRead(BUTTON_PIN) == LOW) {
    if (!isCalibrating) {
      isCalibrating = true;
      calibrationStart = millis();
    }
    
    unsigned long holdTime = millis() - calibrationStart;
    
    // 1 секунда - ПОЛНЫЙ БАК
    if (holdTime >= 1000 && holdTime < 3000) {
      digitalWrite(LED_PIN, HIGH);
      if (holdTime < 1100) {
        calibFull = readFuelSensor();
        if (calibFull <= calibEmpty + 100) {
          calibFull = calibEmpty + 100;
        }
        if (calibFull > 4095) calibFull = 4095;
        fuelLevel = 100;
        saveToEEPROM();
        Serial.println(F("FULL calibrated"));
      }
    }
    // 3 секунды - ПОЛ БАКА
    else if (holdTime >= 3000 && holdTime < 5000) {
      if (holdTime < 3100) {
        int adcHalf = readFuelSensor();
        calibEmpty = adcHalf - (calibFull - adcHalf);
        if (calibEmpty < 0) calibEmpty = 0;
        if (calibEmpty >= calibFull - 100) calibEmpty = calibFull - 100;
        fuelLevel = 50;
        saveToEEPROM();
        Serial.println(F("HALF calibrated"));
      }
    }
    // 5 секунд - ПУСТОЙ БАК
    else if (holdTime >= 5000 && holdTime < 7000) {
      if (holdTime < 5100) {
        calibEmpty = readFuelSensor();
        if (calibEmpty >= calibFull - 100) {
          calibEmpty = calibFull - 100;
        }
        if (calibEmpty < 0) calibEmpty = 0;
        fuelLevel = 0;
        saveToEEPROM();
        Serial.println(F("EMPTY calibrated"));
      }
    }
    // 7 секунд - СБРОС
    else if (holdTime >= 7000) {
      if (holdTime < 7100) {
        calibFull = 4095;
        calibEmpty = 0;
        distanceTraveled = 0;
        totalFuelUsed = 0;
        saveToEEPROM();
        Serial.println(F("RESET"));
      }
    }
  } else {
    if (isCalibrating) {
      isCalibrating = false;
      digitalWrite(LED_PIN, LOW);
    }
  }
}

void setup() {
  Serial.begin(115200);
  Serial.println("\n\nCar Fuel Monitor v1.1.0");
  Serial.println("========================");
  
  pinMode(FUEL_SENSOR_PIN, INPUT);
  pinMode(BUTTON_PIN, INPUT_PULLUP);
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW);
  
  EEPROM.begin(EEPROM_SIZE);
  loadFromEEPROM();
  
  Wire.begin(21, 22);
  
  if (display.begin(SSD1306_SWITCHCAPVCC, SCREEN_ADDRESS)) {
    displayFound = true;
    display.setTextSize(1);
    display.setTextColor(SSD1306_WHITE);
    display.clearDisplay();
    Serial.println(F("OLED OK"));
  } else {
    displayFound = false;
    Serial.println(F("OLED NOT FOUND"));
  }
  
  SerialBT.begin(BT_DEVICE_NAME);
  Serial.print(F("BT: ")); Serial.println(BT_DEVICE_NAME);
  
  if (displayFound) {
    display.println("Car Fuel Monitor");
    display.println("Version 1.1.0");
    display.println("Tank: " + String(tankCapacity) + "L");
    display.println("Loading...");
    display.display();
    delay(1500);
  }
  
  // Первичный расчет
  calculateRange();
  
  lastUpdate = millis();
  lastSaveTime = millis();
  
  Serial.println("Setup complete");
  Serial.println("========================\n");
}

void loop() {
  checkButton();
  
  if (millis() - lastUpdate >= UPDATE_INTERVAL) {
    updateFuelData();
    calculateConsumption();
    drawDisplay();
    sendBluetoothData();
    lastUpdate = millis();
  }
  
  if (SerialBT.available()) {
    String cmd = SerialBT.readStringUntil('\n');
    cmd.trim();
    if (cmd.startsWith("SPEED:")) { processSpeedCommand(cmd); } else if (cmd.length() > 0) {
      processBluetoothCommand(cmd);
    }
  }
  
  bool btState = SerialBT.connected();
  if (btState != bluetoothConnected) {
    bluetoothConnected = btState;
    Serial.println(btState ? F("BT Connected") : F("BT Disconnected"));
  }
  
  delay(10);
}

void processSpeedCommand(String cmd) {
  if (cmd.startsWith("SPEED:")) {
    phoneSpeed = cmd.substring(6).toFloat();
    if (phoneSpeed < 0) phoneSpeed = 0;
    if (phoneSpeed > 300) phoneSpeed = 300;
    Serial.println("Phone speed: " + String(phoneSpeed) + " km/h");
  }
}
