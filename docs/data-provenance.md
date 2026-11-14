# Gangnam parking snapshot provenance

- Publisher: 서울특별시 강남구 (Seoul Gangnam-gu).
- Source: [공공데이터포털 서울특별시 강남구 주차장정보](https://www.data.go.kr/data/15115393/fileData.do), dataset 15115393.
- License: 공공누리 제1유형 / KOGL Type 1 (attribution). Attribution appears in the app, automatic card and this document.
- Row data date: 2025-09-05. Historical public records; not live fees, live vacancies or verified operations.
- Input: 50 records from a preview of the source's stated 53, retrieved on 2026-11-10. No claim of complete Gangnam coverage.
- Output: `app/src/main/assets/gangnam-parking.json`, 50 records, none omitted. All 50 have coordinates. Stable source IDs and names retained, decimal coordinates retained without adjustment, integer fee fields parsed without invented values; missing/unparseable values remain null.

| Normalized field | Original field |
| --- | --- |
| id | 주차장관리번호 |
| name | 주차장명 |
| address | 소재지도로명주소 |
| latitude / longitude | 위도 / 경도 |
| basicMinutes / basicFee | 주차기본시간 / 주차기본요금 |
| addMinutes / addFee | 추가단위시간 / 추가단위요금 |
| sourceDate | 데이터기준일자 |

Other source columns (operating days/hours, spaces, payment methods, telephone, monthly and day tickets, notes, lot type and management details) are omitted from the normalized asset and are not modeled. Road addresses missing in the source remain empty; no address or entrance geocoding is performed. Coordinates identify source locations, not verified entrances.

For a positive stay up to the positive basic interval, the estimate is the positive basic fee. Longer stays add `ceil((stay-basicMinutes)/addMinutes) * addFee` using positive additional components. Missing/nonpositive necessary components yield unknown (including zero fees: never implied free). The initial stay is 60 minutes; the comparison supports 30, 60, 120 and 180 minutes. There is no day-ticket cap, discount, schedule, day/night rule or operational guarantee. A straight-line Haversine radius of 1000 m selects at most three records, ordered by distance then source ID. This is neither walking distance nor driving distance, and the nearest location is not necessarily the cheapest.

Manual fixture cards are separate: their 1,000원/hour display is explicitly fictional. They do not derive from this dataset.
