-- 뷰티 상품 전성분(판매자가 붙여 넣은 원문). 뷰티 소분류 상품만 값이 있다.
-- 필수 여부는 앱이 검사한다. 기존 뷰티 상품은 null이라 NOT NULL을 걸 수 없고, 다음 수정 때 판매자가 넣는다.
-- 번호: #340(협약 수수료율)이 V15를 쓴다. 머지 순서가 바뀌면 develop의 마지막 번호 + 1로 다시 맞춘다.
alter table product add column ingredients_text text;
