import { Suspense } from 'react';
import { QuestionList } from '@/components/QuestionList';

export default function ProblemsPage() {
  return (
    <Suspense fallback={<div className="p-12 text-center text-ink/40">加载中...</div>}>
      <QuestionList
        title="题目"
        subtitle="八股面试题,涵盖 Redis、MySQL、Spring 等核心知识点"
        fixedType="FILL_IN"
      />
    </Suspense>
  );
}
