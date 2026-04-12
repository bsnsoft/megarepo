import { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { api } from '../api/client';
import type { StatusCheck } from '../types/api';

interface TopBarProps {
  onToggleSidebar: () => void;
}

export default function TopBar({ onToggleSidebar }: TopBarProps) {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [searchQuery, setSearchQuery] = useState('');
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const [healthStatus, setHealthStatus] = useState<'UP' | 'DOWN' | 'LOADING'>('LOADING');
  const dropdownRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    api
      .get<StatusCheck>('/status/check')
      .then((s) => setHealthStatus(s.status === 'UP' ? 'UP' : 'DOWN'))
      .catch(() => setHealthStatus('DOWN'));

    const interval = setInterval(() => {
      api
        .get<StatusCheck>('/status/check')
        .then((s) => setHealthStatus(s.status === 'UP' ? 'UP' : 'DOWN'))
        .catch(() => setHealthStatus('DOWN'));
    }, 30000);

    return () => clearInterval(interval);
  }, []);

  useEffect(() => {
    function handleClickOutside(e: MouseEvent) {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target as Node)) {
        setDropdownOpen(false);
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  function handleSearch(e: React.FormEvent) {
    e.preventDefault();
    if (searchQuery.trim()) {
      navigate(`/search?q=${encodeURIComponent(searchQuery.trim())}`);
      setSearchQuery('');
    }
  }

  const healthDotClass =
    healthStatus === 'UP'
      ? 'bg-green-500'
      : healthStatus === 'DOWN'
        ? 'bg-red-500'
        : 'bg-gray-400';

  const healthLabel =
    healthStatus === 'LOADING' ? 'Checking...'
    : healthStatus === 'UP' ? 'System OK'
    : 'System Error';

  return (
    <header className="h-14 bg-white border-b border-gray-200 px-4 sm:px-6 flex items-center gap-3 sm:gap-4 shrink-0 sticky top-0 z-10">
      {/* Hamburger button - mobile only */}
      <button
        className="md:hidden flex items-center justify-center w-9 h-9 rounded-lg text-gray-500 hover:bg-gray-100 transition-colors duration-150 shrink-0"
        onClick={onToggleSidebar}
        aria-label="Toggle sidebar"
      >
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
          <path d="M3 6h18M3 12h18M3 18h18" />
        </svg>
      </button>

      {/* Search */}
      <form className="flex items-center flex-1 max-w-md" onSubmit={handleSearch}>
        <div className="relative w-full">
          <svg className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <circle cx="11" cy="11" r="8" />
            <path d="M21 21l-4.35-4.35" />
          </svg>
          <input
            type="text"
            placeholder="Search components..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full py-2 pl-9 pr-3 bg-gray-100 rounded-lg text-sm text-gray-700 placeholder:text-gray-400 border-none transition-colors duration-150 focus:outline-none focus:ring-2 focus:ring-blue-600/20 focus:bg-white"
          />
        </div>
      </form>

      {/* Right side */}
      <div className="flex items-center gap-3 sm:gap-5 ml-auto">
        {/* Health status */}
        <div className="hidden sm:flex items-center gap-1.5 text-xs text-gray-500">
          <span className={`inline-block w-2 h-2 rounded-full ${healthDotClass}`} />
          <span>{healthLabel}</span>
        </div>

        {/* Mobile: just the health dot */}
        <div className="sm:hidden flex items-center">
          <span className={`inline-block w-2 h-2 rounded-full ${healthDotClass}`} />
        </div>

        {/* User menu */}
        <div className="relative" ref={dropdownRef}>
          <button
            className="flex items-center gap-2 px-2 py-1.5 rounded-md border-none bg-transparent text-sm text-gray-700 cursor-pointer transition-colors duration-150 hover:bg-gray-100"
            onClick={() => setDropdownOpen(!dropdownOpen)}
          >
            <span className="w-7 h-7 rounded-full bg-blue-600 text-white flex items-center justify-center text-xs font-bold shrink-0">
              {user?.username?.[0]?.toUpperCase() ?? '?'}
            </span>
            <span className="font-medium hidden sm:inline">{user?.username ?? 'Unknown'}</span>
            <svg className="hidden sm:block text-gray-400" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M6 9l6 6 6-6" />
            </svg>
          </button>

          {dropdownOpen && (
            <div className="absolute top-[calc(100%+4px)] right-0 bg-white border border-gray-200 rounded-lg shadow-md min-w-[170px] z-50 overflow-hidden">
              <button
                className="block w-full text-left px-4 py-2.5 text-sm text-gray-600 bg-transparent border-none cursor-pointer transition-colors duration-150 hover:bg-gray-50 hover:text-gray-800"
                onClick={() => {
                  setDropdownOpen(false);
                  navigate('/account');
                }}
              >
                Account Settings
              </button>
              <div className="border-t border-gray-100 my-0.5" />
              <button
                className="block w-full text-left px-4 py-2.5 text-sm text-gray-600 bg-transparent border-none cursor-pointer transition-colors duration-150 hover:bg-gray-50 hover:text-gray-800"
                onClick={() => {
                  setDropdownOpen(false);
                  logout();
                  navigate('/login');
                }}
              >
                Sign Out
              </button>
            </div>
          )}
        </div>
      </div>
    </header>
  );
}
