package io.bluetape4k.exposed.core

import java.io.Serializable

/**
 * 키셋/커서 기반 조회 결과와 다음 조회에 사용할 커서를 보관하는 DTO입니다.
 *
 * - `hasNext`가 `true`이면 현재 결과가 비어 있지 않고 `nextCursor`가 있어야 합니다.
 * - `hasNext`가 `false`이면 `nextCursor`는 반드시 `null`이어야 합니다.
 * - `content`는 [ExposedPage]와 동일하게 호출자가 전달한 리스트를 그대로 보관합니다.
 *
 * 이 DTO는 Java serialization을 지원하며 명시적인 `serialVersionUID = 1L`을 사용합니다.
 * 따라서 실제 `content` 원소와 `nextCursor` 값, 그리고 런타임의 content 리스트 구현도
 * 직렬화 가능해야 합니다. generic 타입 경계에는 이를 강제하지 않으므로 호출자가 구체 타입의
 * 직렬화 가능성을 보장해야 합니다.
 *
 * 커서 token의 인코딩·서명·만료·테넌트/권한 범위 및 동일한 정렬·조건 재사용은 여전히
 * 호출자가 책임집니다. DTO 직렬화는 전송용 불투명 cursor token을 대신하지 않습니다.
 */
data class ExposedCursorPage<T, C : Comparable<C>>(
    val content: List<T>,
    val nextCursor: C?,
    val hasNext: Boolean,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }

    init {
        require(hasNext || nextCursor == null) {
            "nextCursor must be null when hasNext is false"
        }
        require(!hasNext || (content.isNotEmpty() && nextCursor != null)) {
            "hasNext requires non-empty content and a nextCursor"
        }
    }
}
