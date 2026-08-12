package io.bluetape4k.exposed.core

/**
 * 키셋/커서 기반 조회 결과와 다음 조회에 사용할 커서를 보관하는 DTO입니다.
 *
 * - `hasNext`가 `true`이면 현재 결과가 비어 있지 않고 `nextCursor`가 있어야 합니다.
 * - `hasNext`가 `false`이면 `nextCursor`는 반드시 `null`이어야 합니다.
 * - `content`는 [ExposedPage]와 동일하게 호출자가 전달한 리스트를 그대로 보관합니다.
 *
 * 커서의 직렬화, 서명, 만료, 테넌트/권한 범위 및 동일한 정렬·조건 재사용은 호출자가
 * 책임집니다.
 */
data class ExposedCursorPage<T, C : Comparable<C>>(
    val content: List<T>,
    val nextCursor: C?,
    val hasNext: Boolean,
) {
    init {
        require(hasNext || nextCursor == null) {
            "nextCursor must be null when hasNext is false"
        }
        require(!hasNext || (content.isNotEmpty() && nextCursor != null)) {
            "hasNext requires non-empty content and a nextCursor"
        }
    }
}
